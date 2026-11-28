package me.rerere.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal open class DeepSeekWebException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class DeepSeekWafException(message: String) : DeepSeekWebException(message)

internal class DeepSeekTokenExpiredException(message: String) : DeepSeekWebException(message)

internal class DeepSeekAccountMutedException(
    message: String,
    val muteUntilEpochSeconds: Double? = null,
) : DeepSeekWebException(message)

internal class DeepSeekRateLimitException(message: String) : DeepSeekWebException(message)

internal class DeepSeekStreamErrorException(
    message: String,
    val finishReason: String? = null,
) : DeepSeekWebException(message)

internal data class DeepSeekSource(
    val url: String,
    val title: String,
    val snippet: String = "",
    val publishedDate: String? = null,
    val citeIndex: Int? = null,
)

internal class DeepSeekFragment(
    val id: Int? = null,
    val type: String,
    val content: StringBuilder = StringBuilder(),
    val queries: MutableList<String> = mutableListOf(),
    val results: MutableList<DeepSeekSource> = mutableListOf(),
    var result: DeepSeekSource? = null,
    var status: String? = null,
)

/**
 * Stateful SSE & JSON Patch (`p` / `o` / `v` + `BATCH`) parser aligned with DeepSeek's frontend `DeltaParser`
 * and `ds_core/src/chat/response.rs`.
 *
 * Key invariants:
 * - `currentPath` and `currentOp` (default `"SET"`) persist across events so frames carrying only `{"v": ...}`
 *   reuse the previous path and operator.
 * - `o == "BATCH"` decomposes `v` with an independent sub-state `(subPath = "", subOp = "SET")` and prefixes
 *   each item's path with `parentPath/`.
 * - Only fragments with `type == "RESPONSE"` contribute to [summary]; `THINK`, `TIP`, `TOOL_SEARCH`, and
 *   `TOOL_OPEN` text are excluded from [summary], while `TOOL_SEARCH` (`results`) and `TOOL_OPEN` (`result`)
 *   populate deduplicated real web [sources].
 */
internal class DeepSeekDeltaParser(
    private val json: Json = SearchService.json,
) {
    private var currentPath: String? = null
    private var currentOp: String = OP_SET

    private var pendingEventType: String? = null
    private val pendingDataLines = mutableListOf<String>()

    private val _fragments = mutableListOf<DeepSeekFragment>()
    private val legacyResponseContent = StringBuilder()
    private val sourcesByUrl = LinkedHashMap<String, DeepSeekSource>()

    var responseMessageId: Long? = null
        private set

    var status: String? = null
        private set

    var quasiStatus: String? = null
        private set

    var accumulatedTokenUsage: Int? = null
        private set

    var closed: Boolean = false
        private set

    val fragments: List<DeepSeekFragment>
        get() = _fragments

    val isDone: Boolean
        get() = closed || status == STATUS_FINISHED || status == STATUS_INCOMPLETE

    val summary: String
        get() {
            val responseFragments = _fragments.filter { it.type == FRAG_RESPONSE }
            if (responseFragments.isNotEmpty()) {
                return responseFragments.joinToString(separator = "") { it.content.toString() }
            }
            return legacyResponseContent.toString()
        }

    val sources: List<DeepSeekSource>
        get() = sourcesByUrl.values.toList()

    /**
     * Parses a complete SSE (or plain-JSON error) payload in one call.
     */
    fun parseAll(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        // Plain JSON response (non-SSE), e.g. biz_code == 5 muted account or code == 40003 invalid token
        if (trimmed.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            if (obj != null) {
                throwIfJsonEnvelopeError(obj)
            }
        }

        for (line in rawText.lineSequence()) {
            feedLine(line)
            if (isDone) break
        }
        finish()
    }

    /**
     * Feeds a single line (without trailing `\r`/`\n`) from an SSE stream.
     */
    fun feedLine(rawLine: String) {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) {
            flushPendingFrame()
            return
        }
        if (line.startsWith(":")) {
            // SSE comment / keep-alive
            return
        }
        when {
            line.startsWith("event:") -> {
                pendingEventType = line.removePrefix("event:").trim()
            }

            line.startsWith("data:") -> {
                val payload = line.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                pendingDataLines.add(payload)
            }

            line.trimStart().startsWith("{") && pendingEventType == null && pendingDataLines.isEmpty() -> {
                // Non-SSE plain JSON body line (such as account mute or token expired envelope)
                val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()
                if (obj != null) {
                    throwIfJsonEnvelopeError(obj)
                    applyDataObject(eventType = null, obj = obj)
                }
            }
        }
    }

    /**
     * Flushes any trailing buffered SSE frame when the stream reaches EOF without a final blank line.
     */
    fun finish() {
        flushPendingFrame()
    }

    private fun flushPendingFrame() {
        val eventType = pendingEventType
        val data = if (pendingDataLines.isNotEmpty()) pendingDataLines.joinToString("\n") else null
        pendingEventType = null
        pendingDataLines.clear()

        if (eventType == null && data.isNullOrBlank()) return
        applyFrame(eventType, data)
    }

    private fun applyFrame(eventType: String?, data: String?) {
        if (eventType == "close") {
            closed = true
            return
        }
        if (data.isNullOrBlank()) return

        val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return

        applyDataObject(eventType, obj)
    }

    private fun applyDataObject(eventType: String?, obj: JsonObject) {
        // Check plain API envelope errors first (e.g., muted account / expired token)
        throwIfJsonEnvelopeError(obj)

        // Check SSE hint / toast errors
        if (eventType == "hint" || eventType == "toast") {
            throwIfUpstreamEventError(eventType, obj)
            return
        }

        // Check inline toast error object
        (obj["toast"] as? JsonObject)?.let { inlineToast ->
            if (inlineToast.string("type").equals("error", ignoreCase = true)) {
                throwIfUpstreamEventError("toast", inlineToast)
            }
        }

        when (eventType) {
            "ready" -> {
                obj.long("response_message_id")?.let { responseMessageId = it }
                return
            }

            "update_session", "title" -> {
                return
            }
        }

        applyPatch(obj)
    }

    private fun applyPatch(obj: JsonObject) {
        obj.string("p")?.let { currentPath = it }
        obj.string("o")?.let { currentOp = it }

        val value = obj["v"] ?: return

        // Initial snapshot: no path yet and v contains "response"
        if (currentPath == null && value is JsonObject) {
            val response = value["response"] as? JsonObject
            if (response != null) {
                applyInitialSnapshot(response)
                return
            }
        }

        val path = currentPath.orEmpty()
        val op = currentOp

        if (op == OP_BATCH && value is JsonArray) {
            applyBatch(path, value)
            return
        }

        applyPath(path, op, value)
    }

    private fun applyBatch(parentPath: String, items: JsonArray) {
        var subPath = ""
        var subOp = OP_SET

        for (item in items) {
            val itemObj = item as? JsonObject ?: continue
            itemObj.string("p")?.let { subPath = it }
            itemObj.string("o")?.let { subOp = it }
            val value = itemObj["v"] ?: continue

            val fullPath = joinPath(parentPath, subPath)
            if (subOp == OP_BATCH && value is JsonArray) {
                applyBatch(fullPath, value)
            } else {
                applyPath(fullPath, subOp, value)
            }
        }
    }

    private fun applyInitialSnapshot(response: JsonObject) {
        response.long("message_id")?.let {
            if (responseMessageId == null) {
                responseMessageId = it
            }
        }
        response.string("status")?.let { status = it }
        response.string("quasi_status")?.let { quasiStatus = it }
        response.int("accumulated_token_usage")?.let { accumulatedTokenUsage = it }

        val fragmentsArray = response["fragments"] as? JsonArray
        if (fragmentsArray != null) {
            _fragments.clear()
            for (fragEl in fragmentsArray) {
                val fragObj = fragEl as? JsonObject ?: continue
                appendParsedFragment(fragObj)
            }
        } else {
            response.string("content")?.takeIf { it.isNotEmpty() }?.let {
                legacyResponseContent.clear()
                legacyResponseContent.append(it)
            }
        }
    }

    private fun applyPath(rawPath: String, op: String, value: JsonElement) {
        val path = rawPath.removePrefix("/")
        when (path) {
            "response/status", "status" -> {
                value.asStringOrNull()?.let { status = it }
                return
            }

            "response/quasi_status", "quasi_status" -> {
                value.asStringOrNull()?.let { quasiStatus = it }
                return
            }

            "response/accumulated_token_usage", "accumulated_token_usage" -> {
                value.asIntOrNull()?.let { accumulatedTokenUsage = it }
                return
            }

            "response/content", "content" -> {
                val text = value.asStringOrNull() ?: return
                if (op == OP_SET) {
                    legacyResponseContent.clear()
                }
                legacyResponseContent.append(text)
                return
            }

            "response/fragments", "fragments" -> {
                val arr = value as? JsonArray ?: return
                if (op == OP_SET) {
                    _fragments.clear()
                }
                for (item in arr) {
                    val fragObj = item as? JsonObject ?: continue
                    appendParsedFragment(fragObj)
                }
                return
            }
        }

        // Fragment field paths: response/fragments/<index>/<field>(/<subpath...>)
        val fragmentPrefix = when {
            path.startsWith("response/fragments/") -> "response/fragments/"
            path.startsWith("fragments/") -> "fragments/"
            else -> null
        } ?: return

        val remainder = path.removePrefix(fragmentPrefix)
        val segments = remainder.split('/')
        if (segments.size < 2) return

        val rawIndex = segments[0].toIntOrNull() ?: return
        val resolvedIndex = if (rawIndex < 0) _fragments.size + rawIndex else rawIndex
        if (resolvedIndex !in _fragments.indices) return

        val fragment = _fragments[resolvedIndex]
        val field = segments[1]

        when (field) {
            "content" -> {
                val text = value.asStringOrNull() ?: return
                if (op == OP_SET) {
                    fragment.content.clear()
                }
                fragment.content.append(text)
            }

            "status" -> {
                value.asStringOrNull()?.let { fragment.status = it }
            }

            "queries" -> {
                if (op == OP_SET) {
                    fragment.queries.clear()
                }
                extractQueries(value, fragment.queries)
            }

            "results" -> {
                if (segments.size == 2) {
                    if (op == OP_SET) {
                        fragment.results.clear()
                    }
                    when (value) {
                        is JsonArray -> {
                            for (item in value) {
                                val srcObj = item as? JsonObject ?: continue
                                parseSource(srcObj)?.let { src ->
                                    fragment.results.add(src)
                                    recordSource(src)
                                }
                            }
                        }

                        is JsonObject -> {
                            parseSource(value)?.let { src ->
                                fragment.results.add(src)
                                recordSource(src)
                            }
                        }

                        else -> Unit
                    }
                } else if (value is JsonObject) {
                    parseSource(value)?.let { src ->
                        fragment.results.add(src)
                        recordSource(src)
                    }
                }
            }

            "result" -> {
                val srcObj = value as? JsonObject ?: return
                parseSource(srcObj)?.let { src ->
                    fragment.result = src
                    recordSource(src)
                }
            }
        }
    }

    private fun appendParsedFragment(obj: JsonObject) {
        val type = obj.string("type") ?: return
        val frag = DeepSeekFragment(
            id = obj.int("id"),
            type = type,
            status = obj.string("status"),
        )
        obj.string("content")?.takeIf { it.isNotEmpty() }?.let {
            frag.content.append(it)
        }
        obj["queries"]?.let { extractQueries(it, frag.queries) }

        val resultsArray = obj["results"] as? JsonArray
        if (resultsArray != null) {
            for (item in resultsArray) {
                val srcObj = item as? JsonObject ?: continue
                parseSource(srcObj)?.let { src ->
                    frag.results.add(src)
                    recordSource(src)
                }
            }
        }

        val singleResult = obj["result"] as? JsonObject
        if (singleResult != null) {
            parseSource(singleResult)?.let { src ->
                frag.result = src
                recordSource(src)
            }
        }

        _fragments.add(frag)
    }

    private fun extractQueries(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonArray -> {
                for (item in element) {
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let(out::add)
                        is JsonObject -> item.string("query")?.takeIf { it.isNotBlank() }?.let(out::add)
                        else -> Unit
                    }
                }
            }

            is JsonObject -> {
                element.string("query")?.takeIf { it.isNotBlank() }?.let(out::add)
            }

            is JsonPrimitive -> {
                element.contentOrNull?.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
    }

    private fun parseSource(obj: JsonObject): DeepSeekSource? {
        val rawUrl = (obj.string("url") ?: obj.string("link"))?.trim() ?: return null
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val title = (obj.string("title") ?: obj.string("site_name") ?: "").trim()
        val snippet = (
            obj.string("snippet")
                ?: obj.string("summary")
                ?: obj.string("content")
                ?: obj.string("description")
                ?: ""
            ).trim()
        val publishedDate = parsePublishedDate(obj)
        val citeIndex = obj.int("cite_index") ?: obj.int("index")
        return DeepSeekSource(
            url = rawUrl,
            title = title,
            snippet = snippet,
            publishedDate = publishedDate,
            citeIndex = citeIndex,
        )
    }

    private fun parsePublishedDate(obj: JsonObject): String? {
        val keys = listOf("published_at", "date", "publish_time", "published_date")
        for (key in keys) {
            val el = obj[key] as? JsonPrimitive ?: continue
            if (el.isString) {
                val str = el.contentOrNull?.trim()
                if (!str.isNullOrEmpty()) return str
            } else {
                val epoch = el.doubleOrNull ?: continue
                if (epoch > 0.0) {
                    val millis = if (epoch > 1e11) epoch.toLong() else (epoch * 1000.0).toLong()
                    return formatTimestamp(millis)
                }
            }
        }
        return null
    }

    private fun recordSource(source: DeepSeekSource) {
        val existing = sourcesByUrl[source.url]
        if (existing == null) {
            sourcesByUrl[source.url] = source
        } else {
            sourcesByUrl[source.url] = DeepSeekSource(
                url = existing.url,
                title = existing.title.ifBlank { source.title },
                snippet = if (source.snippet.length > existing.snippet.length) source.snippet else existing.snippet,
                publishedDate = existing.publishedDate ?: source.publishedDate,
                citeIndex = existing.citeIndex ?: source.citeIndex,
            )
        }
    }

    companion object {
        private const val OP_SET = "SET"
        private const val OP_BATCH = "BATCH"
        private const val FRAG_RESPONSE = "RESPONSE"
        private const val STATUS_FINISHED = "FINISHED"
        private const val STATUS_INCOMPLETE = "INCOMPLETE"

        private fun joinPath(parent: String, child: String): String {
            val p = parent.trim('/')
            val c = child.trim('/')
            return when {
                p.isEmpty() -> c
                c.isEmpty() -> p
                else -> "$p/$c"
            }
        }

        internal fun throwIfJsonEnvelopeError(obj: JsonObject) {
            val code = obj.int("code")
            if (code != null && code != 0) {
                val msg = obj.string("msg").orEmpty()
                if (code == 40003) {
                    throw DeepSeekTokenExpiredException("Token 已失效，请重新获取（$msg）")
                }
                throw DeepSeekWebException("DeepSeek API 错误（code=$code）：${msg.ifBlank { "未知错误" }}")
            }

            val data = obj["data"] as? JsonObject ?: return
            val bizCode = data.int("biz_code") ?: return
            if (bizCode == 0) return

            val bizMsg = data.string("biz_msg").orEmpty()
            val bizData = data["biz_data"] as? JsonObject
            if (bizCode == 5 || bizMsg.contains("mute", ignoreCase = true)) {
                val muteUntil = bizData?.double("mute_until")
                val untilText = muteUntil?.let { ts ->
                    val millis = if (ts > 1e11) ts.toLong() else (ts * 1000.0).toLong()
                    "（预计解封时间：${formatTimestamp(millis)}）"
                }.orEmpty()
                throw DeepSeekAccountMutedException(
                    message = "DeepSeek 账号被临时禁言$untilText${if (bizMsg.isNotBlank()) "：$bizMsg" else ""}",
                    muteUntilEpochSeconds = muteUntil,
                )
            }
            throw DeepSeekWebException("DeepSeek 业务错误（biz_code=$bizCode）：${bizMsg.ifBlank { "未知错误" }}")
        }

        internal fun throwIfUpstreamEventError(eventType: String, obj: JsonObject) {
            val errObj = when {
                obj.string("type").equals("error", ignoreCase = true) -> obj
                (obj["v"] as? JsonObject)?.string("type").equals("error", ignoreCase = true) -> obj["v"] as JsonObject
                else -> return
            }
            val content = (errObj.string("content") ?: errObj.string("msg") ?: "").trim()
            val finishReason = errObj.string("finish_reason")?.trim()

            if (finishReason == "rate_limit_reached" ||
                content.contains("rate_limit", ignoreCase = true) ||
                content.contains("频繁")
            ) {
                throw DeepSeekRateLimitException(
                    buildString {
                        append("DeepSeek 触发频率限制")
                        if (content.isNotBlank()) append("：").append(content)
                        if (!finishReason.isNullOrBlank()) append(" (").append(finishReason).append(')')
                    }
                )
            }

            throw DeepSeekStreamErrorException(
                message = buildString {
                    append("DeepSeek ")
                    append(eventType)
                    append(" 错误")
                    if (content.isNotBlank()) append("：").append(content)
                    if (!finishReason.isNullOrBlank()) append(" (").append(finishReason).append(')')
                },
                finishReason = finishReason,
            )
        }

        private fun formatTimestamp(epochMillis: Long): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return formatter.format(Date(epochMillis))
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.longOrNull

        private fun JsonObject.double(key: String): Double? =
            (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonElement.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement.asIntOrNull(): Int? =
            (this as? JsonPrimitive)?.intOrNull
    }
}
