package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File
import java.security.MessageDigest

private const val TAG = "HomePromptManager"
private const val CACHE_FILE_NAME = "home_prompt_cache.json"
private const val SCRIPT_EDIT_DEBOUNCE_MS = 800L
private const val MIN_FOREGROUND_REFRESH_GAP_MS = 5_000L
private const val NEW_CHAT_STALE_MS = 5 * 60 * 1000L

@Serializable
private data class HomePromptCacheEntry(
    val scriptHash: String,
    val item: HomePromptItem,
    val updatedAtMillis: Long,
)

/**
 * 管理首页自定义短语的本地缓存与静默刷新：
 * - 本地缓存优先渲染：冷启动或新建对话立即展示上次成功缓存的内容，避免空白闪烁；
 * - 静默后台刷新：冷启动、应用从后台回到前台、新建对话超过刷新间隔，以及修改脚本后异步拉取最新数据并平滑更新。
 */
class HomePromptManager(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val service: HomePromptService,
    private val riveLoader: HomePromptRiveLoader,
    private val json: Json,
) {
    private val _currentPrompt = MutableStateFlow<HomePromptItem?>(null)
    val currentPrompt: StateFlow<HomePromptItem?> = _currentPrompt.asStateFlow()

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    private val refreshMutex = Mutex()

    @Volatile
    private var activeScriptHash: String? = null

    @Volatile
    private var lastRefreshAttemptMillis: Long = 0L

    private var refreshJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        var firstConfig = true
        appScope.launch {
            settingsStore.settingsFlow
                .map { !it.init to it.homePromptConfig }
                .distinctUntilChanged()
                .collectLatest { (loaded, config) ->
                    if (!loaded) return@collectLatest
                    if (!config.enabled || config.script.isBlank()) {
                        firstConfig = false
                        activeScriptHash = null
                        refreshJob?.cancel()
                        _currentPrompt.value = null
                        clearCache()
                        return@collectLatest
                    }
                    val hash = sha256(config.script)
                    val hashChanged = activeScriptHash != hash
                    activeScriptHash = hash
                    if (hashChanged) {
                        // 先读本地持久化缓存；脚本变更导致缓存不匹配时立即清空旧展示
                        val cached = readCache(hash)
                        _currentPrompt.value = cached?.item
                    }
                    if (firstConfig) {
                        firstConfig = false
                        // 冷启动立即在后台静默刷新一次
                        triggerRefresh(config.script, hash)
                    } else {
                        // 设置页编辑脚本时防抖，避免每输入一个字符就跑一次 JS 请求
                        delay(SCRIPT_EDIT_DEBOUNCE_MS)
                        triggerRefresh(config.script, hash)
                    }
                }
        }
    }

    /** 应用冷启动进入前台或从后台切回前台时调用 */
    fun onAppForeground() {
        refreshIfStale(MIN_FOREGROUND_REFRESH_GAP_MS)
    }

    /** 距上次刷新超过 [minIntervalMs] 时静默拉取最新内容 */
    fun refreshIfStale(minIntervalMs: Long = NEW_CHAT_STALE_MS) {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return
        val config = settings.homePromptConfig
        if (!config.enabled || config.script.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAttemptMillis < minIntervalMs) return
        val hash = sha256(config.script)
        if (activeScriptHash != hash) return
        triggerRefresh(config.script, hash)
    }

    private fun triggerRefresh(script: String, scriptHash: String) {
        lastRefreshAttemptMillis = System.currentTimeMillis()
        refreshJob?.cancel()
        refreshJob = appScope.launch(Dispatchers.IO) {
            refreshMutex.withLock {
                val result = service.fetch(script)
                // 请求返回期间脚本已变动或已关闭，丢弃过期结果
                if (activeScriptHash != scriptHash) return@withLock
                result.onSuccess { item ->
                    if (item != null) {
                        // 如果包含 .riv 动效，先在后台预热到本地缓存再切换展示，保证淡入淡出无缝衔接
                        riveLoader.prefetch(item)
                        if (activeScriptHash != scriptHash) return@withLock
                        _currentPrompt.value = item
                        writeCache(
                            HomePromptCacheEntry(
                                scriptHash = scriptHash,
                                item = item,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        )
                    } else {
                        _currentPrompt.value = null
                        // 接口明确返回空（如不在活动时间窗内），清掉旧缓存不再展示
                        clearCache()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Silent home prompt refresh failed", error)
                    // 断网或偶发失败时保留现有有效缓存；无缓存则维持 null 不展示
                }
            }
        }
    }

    private suspend fun readCache(expectedHash: String): HomePromptCacheEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile
            if (!file.isFile) return@runCatching null
            val entry = json.decodeFromString<HomePromptCacheEntry>(file.readText())
            entry.takeIf { it.scriptHash == expectedHash && it.item.hasDisplayContent }
        }.getOrNull()
    }

    private suspend fun writeCache(entry: HomePromptCacheEntry) = withContext(Dispatchers.IO) {
        runCatching {
            val target = cacheFile
            val tmp = File(target.parentFile, "$CACHE_FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(entry))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to write home prompt cache", error)
        }
    }

    private suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.delete()
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
