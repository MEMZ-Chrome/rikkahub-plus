package me.rerere.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val HIF_LEIM_URL = "https://hif-leim.deepseek.com/query"
private const val HIF_DLIQ_URL = "https://hif-dliq.deepseek.com/query"

/**
 * Built-in search service backed by DeepSeek's free Web/App web-search backend.
 *
 * Lets DeepSeek perform web search, open pages, and summarize findings, then maps the
 * extracted summary and verified source links into RikkaHub's standard [SearchResult].
 *
 * Strictly enforces user-configurable rate limiting and prompt hygiene (no long prompts,
 * no role/tool tags) to avoid reverse-proxy abuse patterns.
 */
object DeepSeekWebSearchService : SearchService<SearchServiceOptions.DeepSeekWebOptions> {
    override val name: String = "DeepSeek Web"

    private const val BASE_ORIGIN = "https://chat.deepseek.com"
    private const val API_BASE = "$BASE_ORIGIN/api/v0"
    private const val COMPLETION_TARGET_PATH = "/api/v0/chat/completion"

    private const val USER_AGENT = "DeepSeek/2.5.0 Android/35"
    private const val CLIENT_VERSION = "2.5.0"
    private const val CLIENT_PLATFORM = "android"
    private const val CLIENT_LOCALE = "zh_CN"
    private const val CLIENT_BUNDLE_ID = "com.deepseek.chat"
    private const val CLIENT_TIMEZONE_OFFSET = "28800"

    private const val MAX_QUERY_LENGTH = 500
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val SPECIAL_TOKEN_REGEX = Regex("""<[｜|][^｜|<>]{1,64}[｜|]>""")
    private val TOOL_BLOCK_TAG_REGEX = Regex(
        pattern = """<\s*/?\s*(?:tool_call|tool_calls|tool_response|tools|function_calls|function_call|invoke|parameter|antml:[a-zA-Z0-9_:-]+)\b[^>]*>""",
        option = RegexOption.IGNORE_CASE,
    )

    internal val rateLimiter = DeepSeekRateLimiter()
    internal val hifCache = DeepSeekHifCache()
    private val cookieJars = ConcurrentHashMap<String, DeepSeekCookieJar>()

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "使用 DeepSeek 免费网页/App 后端联网搜索并总结提取信息，返回总结与真实来源链接。请合理配置频率限制，避免高频调用触发官方风控或禁言。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    urlHandler.openUri(BASE_ORIGIN)
                }
            ) {
                Text("打开 DeepSeek 网页版")
            }
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekWebOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "A concise search query or short natural question to look up on the web. " +
                            "Plain text only; do not include long system prompts, instructions, or XML/tool tags."
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekWebOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekWebOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val rawQuery = params["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val token = normalizeToken(serviceOptions.token)
            if (token.isBlank()) {
                error("请先配置 DeepSeek Token")
            }

            val prompt = buildCleanPrompt(
                rawQuery = rawQuery,
                rawCustomPrompt = serviceOptions.customPrompt,
            )
            if (prompt.isBlank()) {
                error("搜索内容不能为空")
            }

            val deviceId = deriveDeviceId(token)
            val client = clientFor(token, serviceOptions.cookies)
            val baseHeaders = buildCommonHeaders(token = token, deviceId = deviceId, includeAuth = true)

            rateLimiter.withRateLimit(
                accountKey = deviceId,
                minIntervalSeconds = serviceOptions.minIntervalSeconds.coerceAtLeast(0),
                maxRequestsPerHour = serviceOptions.maxRequestsPerHour.coerceAtLeast(0),
            ) {
                executeSearchFlow(
                    client = client,
                    baseHeaders = baseHeaders,
                    deviceId = deviceId,
                    prompt = prompt,
                    deepSearch = serviceOptions.deepSearch,
                    resultSize = commonOptions.resultSize,
                )
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekWebOptions,
    ): Result<ScrapedResult> =
        Result.failure(UnsupportedOperationException("DeepSeek Web 搜索暂不支持单独网页抓取"))

    private suspend fun executeSearchFlow(
        client: OkHttpClient,
        baseHeaders: Map<String, String>,
        deviceId: String,
        prompt: String,
        deepSearch: Boolean,
        resultSize: Int,
    ): SearchResult {
        val sessionId = createSession(client, baseHeaders)
        val parser = DeepSeekDeltaParser(json)
        var finishedNormally = false

        try {
            val challenge = createPowChallenge(client, baseHeaders)
            val powHeader = withContext(Dispatchers.Default) {
                val answer = DeepSeekHashV1.solve(
                    challenge = challenge.challenge,
                    salt = challenge.salt,
                    expireAt = challenge.expireAt,
                    difficulty = challenge.difficulty,
                    checkCancellation = { coroutineContext.ensureActive() },
                )
                DeepSeekHashV1.buildPowResponseHeader(
                    challenge = challenge.challenge,
                    salt = challenge.salt,
                    answer = answer,
                    signature = challenge.signature,
                    targetPath = challenge.targetPath,
                    algorithm = challenge.algorithm,
                )
            }

            val hifHeaders = hifCache.getHeaders(
                deviceId = deviceId,
                client = client,
                commonHeaders = buildCommonHeaders(token = null, deviceId = deviceId, includeAuth = false),
            )

            streamCompletion(
                client = client,
                baseHeaders = baseHeaders,
                powHeader = powHeader,
                hifHeaders = hifHeaders,
                sessionId = sessionId,
                prompt = prompt,
                deepSearch = deepSearch,
                parser = parser,
            )
            finishedNormally = parser.isDone
        } finally {
            withContext(NonCancellable) {
                val msgId = parser.responseMessageId
                if (!finishedNormally && msgId != null) {
                    runCatching {
                        stopStream(client, baseHeaders, sessionId, msgId)
                    }
                }
                runCatching {
                    deleteSession(client, baseHeaders, sessionId)
                }
            }
        }

        return mapToSearchResult(
            summary = parser.summary,
            sources = parser.sources,
            resultSize = resultSize,
        )
    }

    private suspend fun createSession(
        client: OkHttpClient,
        headers: Map<String, String>,
    ): String {
        val request = buildPostRequest(
            url = "$API_BASE/chat_session/create",
            headers = headers,
            jsonBody = "{}",
        )
        client.newCall(request).awaitCancellable().use { response ->
            val bodyText = readCheckedResponseBody(response, "创建会话")
            val root = parseJsonObject(bodyText, "创建会话")
            DeepSeekDeltaParser.throwIfJsonEnvelopeError(root)
            val bizData = ((root["data"] as? JsonObject)?.get("biz_data") as? JsonObject)
                ?: error("创建会话失败：响应缺少 biz_data")
            val chatSession = bizData["chat_session"] as? JsonObject
            val id = (chatSession?.get("id") ?: bizData["id"])?.jsonPrimitive?.contentOrNull?.trim()
            if (id.isNullOrEmpty()) {
                error("创建会话失败：未返回 chat_session.id")
            }
            return id
        }
    }

    private suspend fun createPowChallenge(
        client: OkHttpClient,
        headers: Map<String, String>,
    ): PowChallenge {
        val body = buildJsonObject {
            put("target_path", COMPLETION_TARGET_PATH)
        }.toString()
        val request = buildPostRequest(
            url = "$API_BASE/chat/create_pow_challenge",
            headers = headers,
            jsonBody = body,
        )
        client.newCall(request).awaitCancellable().use { response ->
            val bodyText = readCheckedResponseBody(response, "获取 PoW 挑战")
            val root = parseJsonObject(bodyText, "获取 PoW 挑战")
            DeepSeekDeltaParser.throwIfJsonEnvelopeError(root)
            val challengeObj = ((root["data"] as? JsonObject)?.get("biz_data") as? JsonObject)
                ?.get("challenge") as? JsonObject
                ?: error("获取 PoW 挑战失败：响应缺少 challenge")

            val algorithm = challengeObj["algorithm"]?.jsonPrimitive?.contentOrNull
                ?: DeepSeekHashV1.ALGORITHM
            if (algorithm != DeepSeekHashV1.ALGORITHM) {
                error("不支持的 DeepSeek PoW 算法：$algorithm")
            }
            return PowChallenge(
                algorithm = algorithm,
                challenge = challengeObj["challenge"]?.jsonPrimitive?.contentOrNull
                    ?: error("PoW 响应缺少 challenge 字段"),
                salt = challengeObj["salt"]?.jsonPrimitive?.contentOrNull
                    ?: error("PoW 响应缺少 salt 字段"),
                signature = challengeObj["signature"]?.jsonPrimitive?.contentOrNull
                    ?: error("PoW 响应缺少 signature 字段"),
                difficulty = challengeObj["difficulty"]?.jsonPrimitive?.longOrNull
                    ?: error("PoW 响应缺少 difficulty 字段"),
                expireAt = challengeObj["expire_at"]?.jsonPrimitive?.longOrNull
                    ?: error("PoW 响应缺少 expire_at 字段"),
                targetPath = challengeObj["target_path"]?.jsonPrimitive?.contentOrNull
                    ?: COMPLETION_TARGET_PATH,
            )
        }
    }

    private suspend fun streamCompletion(
        client: OkHttpClient,
        baseHeaders: Map<String, String>,
        powHeader: String,
        hifHeaders: Map<String, String>,
        sessionId: String,
        prompt: String,
        deepSearch: Boolean,
        parser: DeepSeekDeltaParser,
    ) {
        val payload = buildCompletionPayload(
            sessionId = sessionId,
            prompt = prompt,
            thinkingEnabled = deepSearch,
        )
        val mergedHeaders = LinkedHashMap<String, String>(baseHeaders.size + hifHeaders.size + 1).apply {
            putAll(baseHeaders)
            put("X-Ds-Pow-Response", powHeader)
            putAll(hifHeaders)
        }
        val request = buildPostRequest(
            url = "$API_BASE/chat/completion",
            headers = mergedHeaders,
            jsonBody = payload,
        )

        val call = client.newCall(request)
        call.awaitCancellable().use { response ->
            checkWafChallenge(response)
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body.string() }.getOrDefault("")
                checkPlainJsonError(errBody)
                error("DeepSeek 搜索请求失败（HTTP ${response.code}）：${errBody.take(300)}")
            }

            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("application/json", ignoreCase = true)) {
                val rawBody = response.body.string()
                parser.parseAll(rawBody)
                return
            }

            val source = response.body.source()
            try {
                while (!parser.isDone) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.feedLine(line)
                }
                parser.finish()
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            }
        }
    }

    private suspend fun stopStream(
        client: OkHttpClient,
        headers: Map<String, String>,
        sessionId: String,
        messageId: Long,
    ) {
        val body = buildJsonObject {
            put("chat_session_id", sessionId)
            put("message_id", messageId)
        }.toString()
        val request = buildPostRequest(
            url = "$API_BASE/chat/stop_stream",
            headers = headers,
            jsonBody = body,
        )
        client.newCall(request).awaitCancellable().closeQuietly()
    }

    private suspend fun deleteSession(
        client: OkHttpClient,
        headers: Map<String, String>,
        sessionId: String,
    ) {
        val body = buildJsonObject {
            put("chat_session_id", sessionId)
        }.toString()
        val request = buildPostRequest(
            url = "$API_BASE/chat_session/delete",
            headers = headers,
            jsonBody = body,
        )
        client.newCall(request).awaitCancellable().closeQuietly()
    }

    internal fun buildCompletionPayload(
        sessionId: String,
        prompt: String,
        thinkingEnabled: Boolean,
    ): String = buildJsonObject {
        put("chat_session_id", sessionId)
        put("parent_message_id", JsonNull)
        put("model_type", "default")
        put("prompt", prompt)
        put("ref_file_ids", JsonArray(emptyList()))
        put("thinking_enabled", thinkingEnabled)
        put("search_enabled", true)
        put("action", JsonNull)
        put("source", "input")
        put("preempt", false)
    }.toString()

    internal fun buildCommonHeaders(
        token: String?,
        deviceId: String,
        includeAuth: Boolean,
    ): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        if (includeAuth && !token.isNullOrBlank()) {
            put("Authorization", "Bearer $token")
        }
        put("Accept", "*/*")
        put("Origin", BASE_ORIGIN)
        put("Referer", "$BASE_ORIGIN/")
        put("X-Client-Version", CLIENT_VERSION)
        put("X-Client-Platform", CLIENT_PLATFORM)
        put("X-Client-Locale", CLIENT_LOCALE)
        put("X-Client-Bundle-Id", CLIENT_BUNDLE_ID)
        put("X-Client-Timezone-Offset", CLIENT_TIMEZONE_OFFSET)
        put("X-Device-Id", deviceId)
        put("X-Device-Model", "")
    }

    /**
     * Normalizes user-pasted DeepSeek tokens from any of these forms:
     * 1. Bare token: `abc123...`
     * 2. Bearer header: `Bearer abc123...`
     * 3. `localStorage.userToken` JSON: `{"value":"abc123...","__version":"0"}`
     *    (including JSON-encoded string literals returned from `evaluateJavascript`).
     */
    fun normalizeToken(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        // Unwrap outer JSON string quotes if copied from JS console / evaluateJavascript
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            val unwrapped = runCatching { json.decodeFromString<String>(s) }.getOrNull()
            if (unwrapped != null) {
                s = unwrapped.trim()
            }
        }

        if (s.startsWith("Bearer ", ignoreCase = true)) {
            s = s.substring(7).trim()
        }

        if (s.startsWith("{") && s.endsWith("}")) {
            val obj = runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull()
            val value = obj?.get("value")?.jsonPrimitive?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) {
                return if (value.startsWith("Bearer ", ignoreCase = true)) {
                    value.substring(7).trim()
                } else {
                    value
                }
            }
        }
        return s
    }

    /**
     * Builds a clean, concise plain-text search prompt with zero role tags or tool-calling tags.
     *
     * By default [rawCustomPrompt] is blank so only the clean user query is sent directly.
     */
    internal fun buildCleanPrompt(rawQuery: String, rawCustomPrompt: String = ""): String {
        val cleanQuery = sanitizePlainText(rawQuery).take(MAX_QUERY_LENGTH).trim()
        if (cleanQuery.isEmpty()) return ""

        val cleanCustom = sanitizePlainText(rawCustomPrompt)
            .take(SearchServiceOptions.DeepSeekWebOptions.MAX_CUSTOM_PROMPT_LENGTH)
            .trim()

        return if (cleanCustom.isEmpty()) {
            cleanQuery
        } else {
            "$cleanQuery\n$cleanCustom"
        }
    }

    internal fun sanitizePlainText(input: String): String {
        return input
            .replace(SPECIAL_TOKEN_REGEX, " ")
            .replace(TOOL_BLOCK_TAG_REGEX, " ")
            .replace(Regex("""\r\n?"""), "\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Deterministically derives a stable RFC 4122 UUIDv4 from [token],
     * matching `ds_core/src/accounts/client.rs` (`derive_device_uuid`).
     */
    internal fun deriveDeviceId(token: String): String {
        val seed = "rikkahub:deepseek:x-device-id:$token".encodeToByteArray()
        val hi = fnv1a64(seed, 0L)
        val lo = fnv1a64(seed, 0x9e3779b97f4a7c15UL.toLong())
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i] = ((hi ushr (56 - i * 8)) and 0xFFL).toByte()
            bytes[8 + i] = ((lo ushr (56 - i * 8)) and 0xFFL).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variant 10xx
        return buildString(36) {
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val b = bytes[i].toInt() and 0xFF
                append(HEX_CHARS[b ushr 4])
                append(HEX_CHARS[b and 0x0F])
            }
        }
    }

    internal fun mapToSearchResult(
        summary: String,
        sources: List<DeepSeekSource>,
        resultSize: Int,
    ): SearchResult {
        val cleanSummary = summary.trim()
        if (cleanSummary.isEmpty() && sources.isEmpty()) {
            error("DeepSeek 未返回搜索结果，请稍后重试")
        }

        val limitedSources = sources.take(resultSize.coerceAtLeast(1))
        val summaryText = buildString {
            if (cleanSummary.isNotEmpty()) {
                append(cleanSummary)
            } else {
                append("DeepSeek 检索到了以下网页来源：")
            }
            if (limitedSources.isNotEmpty()) {
                append("\n\n来源参考：\n")
                limitedSources.forEachIndexed { idx, src ->
                    val refNum = src.citeIndex ?: (idx + 1)
                    val title = src.title.ifBlank { src.url }
                    append("[$refNum] ")
                    append(title)
                    append(" - ")
                    append(src.url)
                    if (idx < limitedSources.lastIndex) append('\n')
                }
            }
        }

        val items = buildList(limitedSources.size + 1) {
            add(
                SearchResultItem(
                    title = "DeepSeek Search Summary",
                    url = BASE_ORIGIN,
                    text = summaryText,
                )
            )
            for (src in limitedSources) {
                add(
                    SearchResultItem(
                        title = src.title.ifBlank { src.url },
                        url = src.url,
                        text = src.snippet,
                        publishedDate = src.publishedDate,
                    )
                )
            }
        }
        return SearchResult(
            answer = null,
            items = items,
        )
    }

    private fun clientFor(token: String, cookiesSeed: String): OkHttpClient {
        val jarKey = "${deriveDeviceId(token)}:${cookiesSeed.hashCode()}"
        val jar = cookieJars.getOrCreate(jarKey) {
            DeepSeekCookieJar(cookiesSeed)
        }
        return httpClient.newBuilder()
            .cookieJar(jar)
            .build()
    }

    private fun buildPostRequest(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        return builder.build()
    }

    private fun readCheckedResponseBody(response: Response, actionName: String): String {
        checkWafChallenge(response)
        val bodyText = response.body.string()
        if (!response.isSuccessful) {
            checkPlainJsonError(bodyText)
            error("DeepSeek $actionName 失败（HTTP ${response.code}）：${bodyText.take(300)}")
        }
        return bodyText
    }

    internal fun checkWafChallenge(response: Response) {
        val code = response.code
        val wafAction = response.header("x-amzn-waf-action").orEmpty()
        val cfMitigated = response.header("cf-mitigated").orEmpty()

        val isAwsWaf202 = code == 202 && wafAction.isNotEmpty()
        val isAwsCaptcha405 = code == 405 && wafAction.contains("captcha", ignoreCase = true)
        val isCloudflareChallenge = (code == 403 || code == 429) &&
            cfMitigated.equals("challenge", ignoreCase = true)

        if (isAwsWaf202 || isAwsCaptcha405 || isCloudflareChallenge) {
            throw DeepSeekWafException("触发 WAF 验证，请切换非美区网络或更新 Cookie（HTTP $code）")
        }
    }

    private fun checkPlainJsonError(rawBody: String) {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            if (obj != null) {
                DeepSeekDeltaParser.throwIfJsonEnvelopeError(obj)
            }
        }
    }

    private fun parseJsonObject(rawBody: String, actionName: String): JsonObject {
        val element = runCatching { json.parseToJsonElement(rawBody) }
            .getOrElse { e -> error("DeepSeek $actionName 响应解析失败：${e.message}") }
        return element as? JsonObject
            ?: error("DeepSeek $actionName 响应不是有效的 JSON 对象")
    }

    private fun fnv1a64(data: ByteArray, offset: Long): Long {
        var hash = 0xcbf29ce484222325UL.toLong() xor offset
        for (b in data) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash *= 0x00000100000001B3L
        }
        return hash
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private data class PowChallenge(
        val algorithm: String,
        val challenge: String,
        val salt: String,
        val signature: String,
        val difficulty: Long,
        val expireAt: Long,
        val targetPath: String,
    )

    /**
     * Per-account serializer and rate limiter enforcing:
     * 1. No concurrent completions on the same DeepSeek account (serialized via [Mutex]).
     * 2. Minimum cooldown interval ([minIntervalSeconds]) between consecutive requests.
     * 3. Optional sliding 1-hour window request cap ([maxRequestsPerHour], 0 = unlimited).
     */
    internal class DeepSeekRateLimiter(
        private val nowMillis: () -> Long = { System.currentTimeMillis() },
        private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
    ) {
        private class AccountBucket {
            val mutex = Mutex()
            var lastRequestFinishedAtMs: Long = 0L
            val requestTimestamps = ArrayDeque<Long>()
        }

        private val buckets = ConcurrentHashMap<String, AccountBucket>()

        suspend fun <T> withRateLimit(
            accountKey: String,
            minIntervalSeconds: Int,
            maxRequestsPerHour: Int,
            block: suspend () -> T,
        ): T {
            val bucket = buckets.getOrCreate(accountKey) { AccountBucket() }
            return bucket.mutex.withLock {
                val intervalMs = minIntervalSeconds.coerceAtLeast(0) * 1000L
                val now = nowMillis()

                // 1. Check sliding 1-hour quota first so callers fail fast if quota is exhausted
                if (maxRequestsPerHour > 0) {
                    pruneWindow(bucket, now)
                    if (bucket.requestTimestamps.size >= maxRequestsPerHour) {
                        val oldest = bucket.requestTimestamps.first()
                        val waitMs = (oldest + ONE_HOUR_MS - now).coerceAtLeast(1000L)
                        val waitMinutes = ((waitMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                        throw DeepSeekRateLimitException(
                            "已达到 DeepSeek 频率限制（每小时最多 $maxRequestsPerHour 次），请约 $waitMinutes 分钟后再试"
                        )
                    }
                }

                // 2. Enforce minimum cooldown interval between consecutive requests
                if (intervalMs > 0L && bucket.lastRequestFinishedAtMs > 0L) {
                    val elapsed = now - bucket.lastRequestFinishedAtMs
                    if (elapsed < intervalMs) {
                        sleepMillis(intervalMs - elapsed)
                    }
                }

                val startTs = nowMillis()
                if (maxRequestsPerHour > 0) {
                    pruneWindow(bucket, startTs)
                    if (bucket.requestTimestamps.size >= maxRequestsPerHour) {
                        throw DeepSeekRateLimitException(
                            "已达到 DeepSeek 频率限制（每小时最多 $maxRequestsPerHour 次），请稍后再试"
                        )
                    }
                    bucket.requestTimestamps.addLast(startTs)
                }

                try {
                    block()
                } finally {
                    bucket.lastRequestFinishedAtMs = nowMillis()
                }
            }
        }

        fun clear() {
            buckets.clear()
        }

        private fun pruneWindow(bucket: AccountBucket, now: Long) {
            val cutoff = now - ONE_HOUR_MS
            while (bucket.requestTimestamps.isNotEmpty() && bucket.requestTimestamps.first() <= cutoff) {
                bucket.requestTimestamps.removeFirst()
            }
        }

        companion object {
            private const val ONE_HOUR_MS = 3_600_000L
        }
    }

    /**
     * Best-effort HIF risk-control token cache (`X-Hif-Leim` / `X-Hif-Dliq`).
     *
     * Caches values per device ID respecting `x-hif-ttl` (default 600s, refreshed 30s early),
     * backs off exponentially on failures (e.g. NXDOMAIN on `hif-dliq.deepseek.com`), and
     * never blocks or fails the main completion request.
     */
    internal class DeepSeekHifCache(
        private val leimUrl: String = HIF_LEIM_URL,
        private val dliqUrl: String = HIF_DLIQ_URL,
        private val nowMillis: () -> Long = { System.currentTimeMillis() },
    ) {
        private data class CachedToken(
            val value: String,
            val expiresAtMs: Long,
        )

        private class EndpointState {
            val mutex = Mutex()
            var cached: CachedToken? = null
            var nextAttemptAtMs: Long = 0L
            var consecutiveFailures: Int = 0
        }

        private val endpoints = ConcurrentHashMap<String, EndpointState>()

        suspend fun getHeaders(
            deviceId: String,
            client: OkHttpClient,
            commonHeaders: Map<String, String>,
        ): Map<String, String> = coroutineScope {
            val shortClient = client.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()

            val leimDeferred = async {
                fetchToken("$deviceId:leim", leimUrl, shortClient, commonHeaders)
            }
            val dliqDeferred = async {
                fetchToken("$deviceId:dliq", dliqUrl, shortClient, commonHeaders)
            }

            val leim = leimDeferred.await()
            val dliq = dliqDeferred.await()
            buildMap(2) {
                if (!leim.isNullOrBlank()) put("X-Hif-Leim", leim)
                if (!dliq.isNullOrBlank()) put("X-Hif-Dliq", dliq)
            }
        }

        fun clear() {
            endpoints.clear()
        }

        private suspend fun fetchToken(
            key: String,
            url: String,
            client: OkHttpClient,
            commonHeaders: Map<String, String>,
        ): String? {
            val state = endpoints.getOrCreate(key) { EndpointState() }
            return state.mutex.withLock {
                val now = nowMillis()
                val existing = state.cached
                if (existing != null && existing.expiresAtMs > now) {
                    return@withLock existing.value
                }
                if (state.nextAttemptAtMs > now) {
                    return@withLock existing?.value
                }

                val fetched = runCatching {
                    val reqBuilder = Request.Builder().url(url).get()
                    for ((k, v) in commonHeaders) {
                        if (!k.equals("Authorization", ignoreCase = true)) {
                            reqBuilder.header(k, v)
                        }
                    }
                    client.newCall(reqBuilder.build()).awaitCancellable().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val ttlSecs = resp.header("x-hif-ttl")?.trim()?.toLongOrNull()
                            ?.takeIf { it > 0L } ?: DEFAULT_TTL_SECS
                        val raw = resp.body.string()
                        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@use null
                        val data = root["data"] as? JsonObject ?: return@use null
                        val bizCode = data["biz_code"]?.jsonPrimitive?.longOrNull ?: return@use null
                        if (bizCode != 0L) return@use null
                        val value = (data["biz_data"] as? JsonObject)
                            ?.get("value")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: return@use null
                        val effectiveTtlMs = ((ttlSecs - REFRESH_MARGIN_SECS).coerceAtLeast(1L)) * 1000L
                        CachedToken(value = value, expiresAtMs = now + effectiveTtlMs)
                    }
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                }.getOrNull()

                if (fetched != null) {
                    state.cached = fetched
                    state.consecutiveFailures = 0
                    state.nextAttemptAtMs = 0L
                    fetched.value
                } else {
                    state.consecutiveFailures = (state.consecutiveFailures + 1).coerceAtMost(10)
                    val backoffMs = (INITIAL_BACKOFF_MS shl (state.consecutiveFailures - 1))
                        .coerceAtMost(MAX_BACKOFF_MS)
                    state.nextAttemptAtMs = now + backoffMs
                    existing?.value
                }
            }
        }

        companion object {
            private const val DEFAULT_TTL_SECS = 600L
            private const val REFRESH_MARGIN_SECS = 30L
            private const val INITIAL_BACKOFF_MS = 30_000L
            private const val MAX_BACKOFF_MS = 600_000L
        }
    }

    /**
     * In-memory [CookieJar] seeded from user-provided browser cookies and automatically updated
     * from upstream `Set-Cookie` responses (`cf_clearance`, AWS WAF tokens, session cookies).
     */
    internal class DeepSeekCookieJar(initialCookieHeader: String) : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()

        init {
            seedFromHeader(initialCookieHeader)
        }

        private fun seedFromHeader(header: String) {
            if (header.isBlank()) return
            for (part in header.split(';')) {
                val trimmed = part.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) continue
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isEmpty()) continue
                runCatching {
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain("deepseek.com")
                        .path("/")
                        .build()
                    store[cookieKey(cookie)] = cookie
                }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                // Remove any older seeded or overlapping cookie with the same name so Set-Cookie refreshes cleanly
                val iter = store.entries.iterator()
                while (iter.hasNext()) {
                    val existing = iter.next().value
                    if (existing.name == cookie.name && existing.path == cookie.path) {
                        iter.remove()
                    }
                }
                val key = cookieKey(cookie)
                if (cookie.expiresAt >= now) {
                    store[key] = cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val valid = mutableListOf<Cookie>()
            val iter = store.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val cookie = entry.value
                if (cookie.expiresAt < now) {
                    iter.remove()
                } else if (cookie.matches(url)) {
                    valid.add(cookie)
                }
            }
            return valid
        }

        private fun cookieKey(cookie: Cookie): String = "${cookie.domain}|${cookie.path}|${cookie.name}"
    }
}

private inline fun <K : Any, V : Any> ConcurrentHashMap<K, V>.getOrCreate(
    key: K,
    create: () -> V,
): V {
    return this[key] ?: run {
        val candidate = create()
        putIfAbsent(key, candidate) ?: candidate
    }
}

private suspend fun Call.awaitCancellable(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, _, _ ->
                        response.closeQuietly()
                    }
                } else {
                    response.closeQuietly()
                }
            }
        })
    }
}
