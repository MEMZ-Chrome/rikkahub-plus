package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import app.rive.runtime.kotlin.core.File as RiveCoreFile
import app.rive.runtime.kotlin.core.Rive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections

private const val TAG = "HomePromptRiveLoader"
private const val RIVE_CACHE_DIR = "home_prompt_rive"
private const val MAX_RIVE_BYTES = 10 * 1024 * 1024 // 10 MB 上限，防止异常链接耗尽内存
private const val MAX_DISK_CACHE_FILES = 6
private const val MAX_MEMORY_CACHE_ENTRIES = 4

class LoadedRive(
    val url: String,
    val bytes: ByteArray,
    val aspectRatio: Float,
)

/**
 * 负责 .riv（Rive）动效文件的懒加载初始化、下载、校验与本地磁盘/内存缓存。
 * 只有在真正需要加载 .riv 素材时才会初始化 Rive 原生库，不影响常规启动耗时。
 */
class HomePromptRiveLoader(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    @Volatile
    private var runtimeInitialized = false

    private val memoryCache: MutableMap<String, LoadedRive> = Collections.synchronizedMap(
        object : LinkedHashMap<String, LoadedRive>(MAX_MEMORY_CACHE_ENTRIES + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedRive>?): Boolean =
                size > MAX_MEMORY_CACHE_ENTRIES
        }
    )

    private val cacheDir: File
        get() = File(context.cacheDir, RIVE_CACHE_DIR).apply { mkdirs() }

    /** 确保 Rive 原生运行库已初始化；失败时返回 false 而不崩溃 */
    fun ensureInitialized(): Boolean {
        if (runtimeInitialized) return true
        return synchronized(this) {
            if (runtimeInitialized) return true
            runCatching {
                Rive.init(context.applicationContext)
                runtimeInitialized = true
            }.onFailure { error ->
                Log.w(TAG, "Failed to initialize Rive runtime", error)
            }.isSuccess
        }
    }

    /** 预拉取 item 中包含的浅/深色 .riv 文件到本地缓存，供首页秒开和主题切换无缝展示 */
    suspend fun prefetch(item: HomePromptItem) {
        val urls = buildSet {
            if (item.imageLight.isNotBlank() && item.isRiveMedia(item.imageLight)) add(item.imageLight)
            if (item.imageDark.isNotBlank() && item.isRiveMedia(item.imageDark)) add(item.imageDark)
        }
        urls.forEach { url ->
            runCatching { load(url) }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Prefetch .riv failed: $url", error)
            }
        }
    }

    /**
     * 读取 .riv 字节并解析画板比例：
     * 优先命中内存缓存 → 磁盘缓存 → 走应用 OkHttpClient（含代理与 UA）下载并落盘。
     * 任一环节失败或文件头/结构不合法时返回 null。
     */
    suspend fun load(url: String): LoadedRive? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        memoryCache[url]?.let { return@withContext it }

        if (!ensureInitialized()) return@withContext null

        val cachedFile = File(cacheDir, "${sha256(url)}.riv")
        if (cachedFile.isFile) {
            val bytes = runCatching { cachedFile.readBytes() }.getOrNull()
            if (bytes != null && hasRiveHeader(bytes)) {
                val aspectRatio = inspectAspectRatio(bytes)
                if (aspectRatio != null) {
                    cachedFile.setLastModified(System.currentTimeMillis())
                    return@withContext LoadedRive(url, bytes, aspectRatio).also {
                        memoryCache[url] = it
                    }
                }
            }
            // 损坏的磁盘缓存直接删掉重新拉取
            runCatching { cachedFile.delete() }
        }

        val downloaded = downloadBytes(url) ?: return@withContext null
        if (!hasRiveHeader(downloaded)) {
            Log.w(TAG, "Downloaded payload is not a valid Rive file (missing RIVE header): $url")
            return@withContext null
        }

        val aspectRatio = inspectAspectRatio(downloaded) ?: return@withContext null
        writeDiskCache(cachedFile, downloaded)
        pruneDiskCache()

        LoadedRive(url, downloaded, aspectRatio).also {
            memoryCache[url] = it
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download .riv ($url): HTTP ${response.code}")
                    return null
                }
                val contentLength = response.body.contentLength()
                if (contentLength > MAX_RIVE_BYTES) {
                    Log.w(TAG, ".riv file too large ($contentLength bytes): $url")
                    return null
                }
                val sink = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                response.body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_RIVE_BYTES) {
                            Log.w(TAG, ".riv stream exceeded size limit: $url")
                            return null
                        }
                        sink.write(buffer, 0, read)
                    }
                }
                sink.toByteArray()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            Log.w(TAG, "Error downloading .riv ($url)", error)
            null
        }
    }

    /** 用 Rive 原生解析器校验字节有效性并读取默认画板宽高比 */
    private fun inspectAspectRatio(bytes: ByteArray): Float? = runCatching {
        val riveFile = RiveCoreFile(bytes = bytes)
        try {
            val bounds = riveFile.firstArtboard.bounds
            val width = bounds.width()
            val height = bounds.height()
            if (width > 0f && height > 0f) {
                (width / height).coerceIn(0.4f, 4f)
            } else {
                1f
            }
        } finally {
            riveFile.release()
        }
    }.onFailure { error ->
        Log.w(TAG, "Invalid .riv binary", error)
    }.getOrNull()

    private fun writeDiskCache(target: File, bytes: ByteArray) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to write .riv cache", error)
        }
    }

    private fun pruneDiskCache() {
        runCatching {
            val files = cacheDir.listFiles { file -> file.isFile && file.name.endsWith(".riv") }
                ?: return
            if (files.size <= MAX_DISK_CACHE_FILES) return
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_DISK_CACHE_FILES)
                .forEach { it.delete() }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        internal fun hasRiveHeader(bytes: ByteArray): Boolean =
            bytes.size >= 4 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'V'.code.toByte() &&
                bytes[3] == 'E'.code.toByte()
    }
}
