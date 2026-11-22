package me.rerere.rikkahub.service

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.js.injectFetch
import okhttp3.OkHttpClient

/** 判断 URL 路径是否指向 .riv（Rive）动效文件（忽略 query 和 fragment） */
fun isRiveUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').trim()
    return path.endsWith(".riv", ignoreCase = true)
}

/**
 * 首页新对话空状态展示的数据。
 * 支持浅/深色双素材（静态图、GIF、SVG、.riv Rive 动效，只填其中一张时自动回退另一张）；
 * 图和短语均为空时不展示；点击后把 [prompt]（为空时回退到 [phrase]）写入输入框。
 */
@Serializable
data class HomePromptItem(
    val imageLight: String = "",
    val imageDark: String = "",
    val phrase: String = "",
    val prompt: String = "",
    val isRive: Boolean = false,
) {
    val hasDisplayContent: Boolean
        get() = imageLight.isNotBlank() || imageDark.isNotBlank() || phrase.isNotBlank()

    val insertText: String
        get() = prompt.ifBlank { phrase }

    fun imageForTheme(darkTheme: Boolean): String = if (darkTheme) {
        imageDark.ifBlank { imageLight }
    } else {
        imageLight.ifBlank { imageDark }
    }

    fun isRiveMedia(url: String): Boolean = isRive || isRiveUrl(url)
}

/**
 * 在 QuickJS 沙箱中执行用户自定义的首页短语脚本，接口形式与搜索里的 Custom JS 保持一致：
 * 提供 `fetch(url, options?)`，调用用户实现的 `getHomePrompt()`（支持同步或 async）。
 */
class HomePromptService(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(script: String): Result<HomePromptItem?> = withContext(Dispatchers.IO) {
        runCatching {
            if (script.isBlank()) return@runCatching null
            val rawJson = executeScript(script) ?: return@runCatching null
            parseResult(rawJson)
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun executeScript(userScript: String): String? =
        withTimeoutOrNull(TIMEOUT_MS) {
            quickJs(Dispatchers.IO) {
                memoryLimit = MEMORY_LIMIT_BYTES
                maxStackSize = MAX_STACK_BYTES
                evaluationTimeoutMillis = TIMEOUT_MS
                evaluate<Unit>(
                    "globalThis.console = { log() {}, info() {}, warn() {}, error() {}, debug() {} }; void 0;"
                )
                injectFetch(httpClient)
                evaluate<Unit>(userScript + "\n;void 0;")

                // 支持同步和 async 函数；返回 null / undefined 时视为留空不显示
                evaluate<String?>(
                    """
                    await (async function() {
                        if (typeof getHomePrompt !== "function") {
                            throw new Error("getHomePrompt() function is not defined");
                        }
                        const result = await getHomePrompt();
                        if (result == null) return null;
                        return JSON.stringify(result);
                    })()
                    """.trimIndent()
                )
            }
        } ?: error("JavaScript execution timed out after ${TIMEOUT_MS / 1000}s")

    internal fun parseResult(rawJson: String): HomePromptItem? {
        val element = json.parseToJsonElement(rawJson)
        if (element is JsonNull) return null
        val obj = element as? JsonObject ?: error("getHomePrompt() must return an object or null")
        val (imageLight, imageDark, fromRiveField) = parseMediaPair(obj)
        val phrase = obj.stringOrBlank("phrase")
            .ifBlank { obj.stringOrBlank("welcomeMessage") }
            .ifBlank { obj.stringOrBlank("text") }
        val prompt = obj.stringOrBlank("prompt")
        val mediaType = obj.stringOrBlank("imageType")
            .ifBlank { obj.stringOrBlank("mediaType") }
            .ifBlank { obj.stringOrBlank("type") }
            .lowercase()
        val explicitRive = (obj["isRive"] as? JsonPrimitive)?.booleanOrNull == true ||
            fromRiveField ||
            mediaType == "riv" ||
            mediaType == "rive"
        return HomePromptItem(
            imageLight = imageLight,
            imageDark = imageDark,
            phrase = phrase,
            prompt = prompt,
            isRive = explicitRive,
        ).takeIf { it.hasDisplayContent }
    }

    /**
     * 兼容多种素材写法（静态图 / GIF / SVG / .riv 均适用）：
     * - 字符串：`image: "https://..."` 或 `rive: "https://....riv"`
     * - 对象：`image: { light: "...", dark: "..." }`
     * - 双元素数组：`image: ["lightUrl", "darkUrl"]`（兼容 Kimi `[light, dark]` 格式）
     * - 分离字段：`imageLight` / `imageDark` 或 `riveLight` / `riveDark`
     */
    private fun parseMediaPair(obj: JsonObject): Triple<String, String, Boolean> {
        val imageElement = obj["image"] ?: obj["imageUrl"]
        extractPair(imageElement)?.let { (light, dark) ->
            return Triple(light, dark, false)
        }
        val imageLight = obj.stringOrBlank("imageLight")
        val imageDark = obj.stringOrBlank("imageDark")
        if (imageLight.isNotEmpty() || imageDark.isNotEmpty()) {
            return Triple(imageLight, imageDark, false)
        }

        val riveElement = obj["rive"] ?: obj["riveUrl"]
        extractPair(riveElement)?.let { (light, dark) ->
            return Triple(light, dark, true)
        }
        val riveLight = obj.stringOrBlank("riveLight")
        val riveDark = obj.stringOrBlank("riveDark")
        if (riveLight.isNotEmpty() || riveDark.isNotEmpty()) {
            return Triple(riveLight, riveDark, true)
        }

        return Triple("", "", false)
    }

    private fun extractPair(element: JsonElement?): Pair<String, String>? =
        when (element) {
            is JsonPrimitive -> {
                val url = element.contentOrNull?.trim().orEmpty()
                if (url.isNotEmpty()) url to "" else null
            }

            is JsonArray -> {
                val light = (element.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val dark = (element.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (light.isNotEmpty() || dark.isNotEmpty()) light to dark else null
            }

            is JsonObject -> {
                val light = element.stringOrBlank("light")
                val dark = element.stringOrBlank("dark")
                if (light.isNotEmpty() || dark.isNotEmpty()) light to dark else null
            }

            else -> null
        }

    private fun JsonObject.stringOrBlank(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        private const val MAX_STACK_BYTES = 256L * 1024
    }
}
