package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

private const val GITHUB_ORIGIN = "https://github.com"
private const val REPO_PATH = "MEMZ-Chrome/rikkahub-plus"

// 只看本仓库的 release；上游用的是 updates.rikka-ai.com，包名不同装不上
private const val API_URL = "https://api.github.com/repos/$REPO_PATH/releases/latest"
private const val WEB_LATEST_URL = "$GITHUB_ORIGIN/$REPO_PATH/releases/latest"
private const val DEFAULT_UNIVERSAL_APK_NAME = "app-github-universal-release.apk"
private const val FALLBACK_PUBLISHED_AT = "1970-01-01T00:00:00Z"
private const val MAX_PROXY_REDIRECTS = 3

private val TRAILING_TARGET_SCHEME_REGEX = Regex("(?i)/?https?:/{1,2}$")
private val RELEASE_TAG_URL_REGEX = Regex(
    """/$REPO_PATH/releases/tag/([^/?#"'\s<>]+)""",
    RegexOption.IGNORE_CASE,
)
private val RELEASE_DOWNLOAD_TAG_REGEX = Regex(
    """/$REPO_PATH/releases/download/([^/?#"'\s<>]+)/""",
    RegexOption.IGNORE_CASE,
)
private val RELEASES_INDEX_URL_REGEX = Regex(
    """/$REPO_PATH/releases/?(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)
private val RELATIVE_TIME_REGEX = Regex(
    """<relative-time\b[^>]*\bdatetime=["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)
private val MARKDOWN_BODY_OPEN_REGEX = Regex(
    """<div\b[^>]*\bclass=["'][^"']*\bmarkdown-body\b[^"']*["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val DIV_TAG_REGEX = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ASSET_LIST_ITEM_REGEX = Regex(
    """<li\b[^>]*>.*?</li>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val APK_HREF_REGEX = Regex(
    """href=["'](/$REPO_PATH/releases/download/[^"']+\.apk)["']""",
    RegexOption.IGNORE_CASE,
)
private val ASSET_SIZE_REGEX = Regex("""\b\d+(?:\.\d+)?\s*(?:B|KB|MB|GB)\b""")
private val HEADING_REGEX = Regex(
    """<h([1-6])\b[^>]*>(.*?)</h\1>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANCHOR_REGEX = Regex(
    """<a\b[^>]*\bhref=["']([^"']+)["'][^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val INLINE_CODE_REGEX = Regex(
    """<code\b[^>]*>(.*?)</code>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val LIST_ITEM_OPEN_REGEX = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
private val LIST_ITEM_CLOSE_REGEX = Regex("""</li>""", RegexOption.IGNORE_CASE)
private val LINE_BREAK_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val PARAGRAPH_CLOSE_REGEX = Regex("""</p>""", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val NUMERIC_ENTITY_REGEX = Regex("""&#(x?[0-9a-fA-F]+);""")
private val EXCESS_NEWLINES_REGEX = Regex("""\n{3,}""")

internal enum class GithubProxyUrlStyle {
    /** https://ghfast.top/https://github.com/... */
    WITH_SCHEME,

    /** https://ghfast.top/github.com/... */
    WITHOUT_SCHEME,
}

private class ProxyUrlStyleException(message: String) : Exception(message)

/**
 * 校验并规范化更新代理域名前缀，例如：
 * - `ghfast.top` -> `https://ghfast.top`
 * - `https://ghfast.top/` -> `https://ghfast.top`
 * - `https://ghfast.top/https://` -> `https://ghfast.top`
 * - `https://proxy.example.com/gh/` -> `https://proxy.example.com/gh`
 * 留空或无效地址返回 `null`。
 */
fun normalizeUpdateProxyDomain(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isWhitespace() }) return null

    // 兼容用户把 "https://ghfast.top/https://" 整段粘贴进来的情况
    val withoutTrailingScheme = trimmed
        .replace(TRAILING_TARGET_SCHEME_REGEX, "")
        .trimEnd('/')
    if (withoutTrailingScheme.isEmpty()) return null

    val withScheme = if ("://" in withoutTrailingScheme) {
        withoutTrailingScheme
    } else {
        "https://$withoutTrailingScheme"
    }

    val parsed = withScheme.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.query != null || parsed.fragment != null) return null

    val host = parsed.host
    val hasValidHost = host.contains('.') || host.equals("localhost", ignoreCase = true) || host.contains(':')
    if (!hasValidHost) return null

    return parsed.toString().trimEnd('/')
}

internal fun applyGithubProxy(
    targetUrl: String,
    proxyDomain: String,
    style: GithubProxyUrlStyle = GithubProxyUrlStyle.WITH_SCHEME,
): String {
    val prefix = normalizeUpdateProxyDomain(proxyDomain) ?: return targetUrl
    if (targetUrl.startsWith("$prefix/", ignoreCase = true)) {
        return targetUrl
    }
    val target = when (style) {
        GithubProxyUrlStyle.WITH_SCHEME -> targetUrl
        GithubProxyUrlStyle.WITHOUT_SCHEME ->
            targetUrl.removePrefix("https://").removePrefix("http://")
    }
    return "$prefix/$target"
}

class UpdateChecker(
    client: OkHttpClient,
    private val settingsStore: SettingsStore,
    appScope: AppScope,
) {
    private val fetcher = UpdateReleaseFetcher(client = client)
    private val refreshTrigger = MutableStateFlow(0)

    @OptIn(FlowPreview::class)
    val updateState: StateFlow<UiState<UpdateInfo>> = combine(
        settingsStore.settingsFlow
            .filter { !it.init }
            .map { it.networkSetting.updateProxyDomain.trim() }
            .distinctUntilChanged()
            .debounce(500),
        refreshTrigger,
    ) { proxyDomain, _ ->
        proxyDomain
    }.flatMapLatest { proxyDomain ->
        checkUpdate(proxyDomain)
    }.stateIn(
        scope = appScope,
        started = SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    fun refresh() {
        refreshTrigger.value++
    }

    suspend fun checkUpdateOnce(
        proxyDomain: String = settingsStore.settingsFlow.value.networkSetting.updateProxyDomain,
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            Result.success(
                fetcher.fetchUpdateInfo(
                    proxyDomain = proxyDomain.trim(),
                    timeoutSeconds = 15L,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun checkUpdate(proxyDomain: String): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    fetcher.fetchUpdateInfo(proxyDomain)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(download.name)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 设置文件保存路径
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            // 你可以保存返回的downloadId到本地，以便后续查询下载进度或状态
        }.onFailure {
            Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
            context.openUrl(download.url) // 跳转到下载页面
        }
    }
}

internal class UpdateReleaseFetcher(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val userAgent: String = "RikkaHubPlus ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}",
) {
    suspend fun fetchUpdateInfo(
        proxyDomain: String,
        timeoutSeconds: Long? = null,
    ): UpdateInfo {
        val trimmedProxy = proxyDomain.trim()
        if (trimmedProxy.isEmpty()) {
            return fetchDirectFromApi(timeoutSeconds)
        }
        val normalizedProxy = normalizeUpdateProxyDomain(trimmedProxy)
            ?: throw IllegalArgumentException("Invalid update proxy domain: $trimmedProxy")
        return fetchViaGithubProxy(normalizedProxy, timeoutSeconds)
    }

    private suspend fun fetchDirectFromApi(timeoutSeconds: Long?): UpdateInfo {
        val httpClient = if (timeoutSeconds != null) {
            buildClient(followRedirects = true, timeoutSeconds = timeoutSeconds)
        } else {
            client
        }
        httpClient.newCall(
            Request.Builder()
                .url(API_URL)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", userAgent)
                .build()
        ).await().use { response ->
            return when {
                response.isSuccessful ->
                    json.decodeFromString<GithubRelease>(response.body.string()).toUpdateInfo()
                // 还没发过 release，当成没有更新，不弹错误卡片
                response.code == 404 -> noUpdate()
                else -> throw Exception("Failed to fetch update info (HTTP ${response.code})")
            }
        }
    }

    private suspend fun fetchViaGithubProxy(
        normalizedProxy: String,
        timeoutSeconds: Long?,
    ): UpdateInfo {
        // 禁止 OkHttp 自动跟随跳转，避免代理透传 github.com 的 302 Location 后绕过代理直连 GitHub
        val httpClient = buildClient(followRedirects = false, timeoutSeconds = timeoutSeconds)
        val styles = listOf(GithubProxyUrlStyle.WITH_SCHEME, GithubProxyUrlStyle.WITHOUT_SCHEME)
        var lastStyleError: ProxyUrlStyleException? = null
        for (style in styles) {
            try {
                return fetchViaGithubProxyWithStyle(httpClient, normalizedProxy, style)
            } catch (e: ProxyUrlStyleException) {
                lastStyleError = e
            }
        }
        throw Exception(lastStyleError?.message ?: "Failed to fetch update info via proxy", lastStyleError)
    }

    private suspend fun fetchViaGithubProxyWithStyle(
        httpClient: OkHttpClient,
        normalizedProxy: String,
        style: GithubProxyUrlStyle,
    ): UpdateInfo {
        return when (val latest = resolveLatestReleaseViaProxy(httpClient, normalizedProxy, style)) {
            ProxiedLatestResolution.NoReleases -> noUpdate()
            is ProxiedLatestResolution.ApiRelease ->
                latest.release.toUpdateInfo(proxyDomain = normalizedProxy, style = style)
            is ProxiedLatestResolution.TagResolved -> {
                val tag = latest.tag
                val pageHtml = latest.pageHtml ?: fetchProxiedHtmlBestEffort(
                    httpClient = httpClient,
                    targetGithubUrl = "$GITHUB_ORIGIN/$REPO_PATH/releases/tag/$tag",
                    normalizedProxy = normalizedProxy,
                    style = style,
                )
                val assetsHtml = fetchProxiedHtmlBestEffort(
                    httpClient = httpClient,
                    targetGithubUrl = "$GITHUB_ORIGIN/$REPO_PATH/releases/expanded_assets/$tag",
                    normalizedProxy = normalizedProxy,
                    style = style,
                )
                val publishedAt = pageHtml?.let(::parseReleasePublishedAtFromHtml)
                    ?: assetsHtml?.let(::parseReleasePublishedAtFromHtml)
                    ?: FALLBACK_PUBLISHED_AT
                val changelog = pageHtml?.let(::parseReleaseChangelogFromHtml).orEmpty()
                val downloads = assetsHtml
                    ?.let { parseExpandedAssetsFromHtml(it, normalizedProxy, style) }
                    .orEmpty()
                    .ifEmpty { listOf(defaultUniversalDownload(tag, normalizedProxy, style)) }
                UpdateInfo(
                    version = tag.removePrefix("v"),
                    publishedAt = publishedAt,
                    changelog = changelog,
                    downloads = downloads,
                )
            }
        }
    }

    private suspend fun resolveLatestReleaseViaProxy(
        httpClient: OkHttpClient,
        normalizedProxy: String,
        style: GithubProxyUrlStyle,
    ): ProxiedLatestResolution {
        val proxyHost = normalizedProxy.toHttpUrlOrNull()?.host
        var currentUrl = applyGithubProxy(WEB_LATEST_URL, normalizedProxy, style)
        repeat(MAX_PROXY_REDIRECTS + 1) {
            httpClient.newCall(
                Request.Builder()
                    .url(currentUrl)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", userAgent)
                    .build()
            ).await().use { response ->
                when {
                    response.code in 301..308 -> {
                        val location = response.header("Location")?.trim().orEmpty()
                        if (location.isEmpty()) {
                            throw ProxyUrlStyleException("Empty redirect location (HTTP ${response.code})")
                        }
                        extractReleaseTagFromUrl(location)?.let { tag ->
                            return ProxiedLatestResolution.TagResolved(tag)
                        }
                        if (isRepoReleasesIndexUrl(location)) {
                            return ProxiedLatestResolution.NoReleases
                        }
                        currentUrl = resolveProxiedRedirectUrl(
                            currentUrl = currentUrl,
                            location = location,
                            normalizedProxy = normalizedProxy,
                            proxyHost = proxyHost,
                            style = style,
                        ) ?: throw ProxyUrlStyleException("Unexpected redirect: $location")
                    }
                    response.isSuccessful -> {
                        val body = response.body.string()
                        val trimmedBody = body.trimStart()
                        if (trimmedBody.startsWith("{")) {
                            runCatching { json.decodeFromString<GithubRelease>(trimmedBody) }
                                .getOrNull()
                                ?.takeIf { it.tagName.isNotBlank() && it.assets.isNotEmpty() }
                                ?.let { return ProxiedLatestResolution.ApiRelease(it) }

                            runCatching { json.decodeFromString<GithubWebLatestJson>(trimmedBody) }
                                .getOrNull()
                                ?.tagName
                                ?.takeIf { it.isNotBlank() }
                                ?.let { return ProxiedLatestResolution.TagResolved(it) }
                        }
                        extractReleaseTagFromHtml(body)?.let { tag ->
                            return ProxiedLatestResolution.TagResolved(tag = tag, pageHtml = body)
                        }
                        throw ProxyUrlStyleException("Proxy returned unrecognized response for latest release")
                    }
                    else -> throw ProxyUrlStyleException("Proxy returned HTTP ${response.code}")
                }
            }
        }
        throw ProxyUrlStyleException("Too many redirects from update proxy")
    }

    private suspend fun fetchProxiedHtmlBestEffort(
        httpClient: OkHttpClient,
        targetGithubUrl: String,
        normalizedProxy: String,
        style: GithubProxyUrlStyle,
    ): String? {
        return try {
            val proxyHost = normalizedProxy.toHttpUrlOrNull()?.host
            var currentUrl = applyGithubProxy(targetGithubUrl, normalizedProxy, style)
            repeat(MAX_PROXY_REDIRECTS + 1) {
                httpClient.newCall(
                    Request.Builder()
                        .url(currentUrl)
                        .get()
                        .addHeader("Accept", "text/html,application/xhtml+xml")
                        .addHeader("User-Agent", userAgent)
                        .build()
                ).await().use { response ->
                    when {
                        response.code in 301..308 -> {
                            val location = response.header("Location")?.trim().orEmpty()
                            if (location.isEmpty()) return null
                            currentUrl = resolveProxiedRedirectUrl(
                                currentUrl = currentUrl,
                                location = location,
                                normalizedProxy = normalizedProxy,
                                proxyHost = proxyHost,
                                style = style,
                            ) ?: return null
                        }
                        response.isSuccessful -> return response.body.string()
                        else -> return null
                    }
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun buildClient(followRedirects: Boolean, timeoutSeconds: Long? = null): OkHttpClient {
        return client.newBuilder().apply {
            followRedirects(followRedirects)
            followSslRedirects(followRedirects)
            if (timeoutSeconds != null) {
                connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            }
        }.build()
    }

    private fun noUpdate() = UpdateInfo(
        version = currentVersion,
        publishedAt = FALLBACK_PUBLISHED_AT,
        changelog = "",
        downloads = emptyList(),
    )
}

private sealed interface ProxiedLatestResolution {
    data object NoReleases : ProxiedLatestResolution
    data class ApiRelease(val release: GithubRelease) : ProxiedLatestResolution
    data class TagResolved(
        val tag: String,
        val pageHtml: String? = null,
    ) : ProxiedLatestResolution
}

@Serializable
private data class GithubWebLatestJson(
    @SerialName("tag_name") val tagName: String? = null,
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
) {
    fun toUpdateInfo(
        proxyDomain: String = "",
        style: GithubProxyUrlStyle = GithubProxyUrlStyle.WITH_SCHEME,
    ): UpdateInfo {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // 有通用包就只给通用包，省得用户在三份里挑
        val picked = apks.filter { it.name.contains("universal", ignoreCase = true) }.ifEmpty { apks }
        return UpdateInfo(
            version = tagName.removePrefix("v"),
            publishedAt = publishedAt?.takeIf(::isValidInstant) ?: FALLBACK_PUBLISHED_AT,
            changelog = body.orEmpty(),
            downloads = picked.map {
                UpdateDownload(
                    name = it.name,
                    url = applyGithubProxy(it.downloadUrl, proxyDomain, style),
                    size = formatSize(it.size),
                )
            },
        )
    }
}

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

internal fun extractReleaseTagFromUrl(url: String): String? {
    val raw = RELEASE_TAG_URL_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
}

internal fun extractReleaseTagFromHtml(html: String): String? {
    extractReleaseTagFromUrl(html)?.let { return it }
    val raw = RELEASE_DOWNLOAD_TAG_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
}

internal fun isRepoReleasesIndexUrl(url: String): Boolean = RELEASES_INDEX_URL_REGEX.containsMatchIn(url)

internal fun resolveProxiedRedirectUrl(
    currentUrl: String,
    location: String,
    normalizedProxy: String,
    proxyHost: String?,
    style: GithubProxyUrlStyle,
): String? {
    if (location.startsWith("/$REPO_PATH/", ignoreCase = true)) {
        return applyGithubProxy("$GITHUB_ORIGIN$location", normalizedProxy, style)
    }
    if (location.startsWith("/")) {
        val resolved = currentUrl.toHttpUrlOrNull()?.resolve(location) ?: return null
        return resolved.toString().takeIf {
            proxyHost != null && resolved.host.equals(proxyHost, ignoreCase = true)
        }
    }
    val targetUrl = location.toHttpUrlOrNull() ?: return null
    return when {
        targetUrl.host.equals("github.com", ignoreCase = true) ->
            applyGithubProxy(location, normalizedProxy, style)
        proxyHost != null && targetUrl.host.equals(proxyHost, ignoreCase = true) ->
            location
        else -> null
    }
}

internal fun parseReleasePublishedAtFromHtml(html: String): String? {
    for (match in RELATIVE_TIME_REGEX.findAll(html)) {
        val candidate = match.groupValues[1].trim()
        if (isValidInstant(candidate)) {
            return candidate
        }
    }
    return null
}

internal fun parseReleaseChangelogFromHtml(html: String): String {
    val openMatch = MARKDOWN_BODY_OPEN_REGEX.find(html) ?: return ""
    val contentStart = openMatch.range.last + 1
    var depth = 1
    var contentEnd = -1
    for (tagMatch in DIV_TAG_REGEX.findAll(html, startIndex = contentStart)) {
        val isClosing = tagMatch.groupValues[1] == "/"
        if (isClosing) {
            depth--
            if (depth == 0) {
                contentEnd = tagMatch.range.first
                break
            }
        } else {
            depth++
        }
    }
    if (contentEnd < contentStart) return ""
    val innerHtml = html.substring(contentStart, contentEnd)
    return htmlToSimpleMarkdown(innerHtml)
}

internal fun parseExpandedAssetsFromHtml(
    html: String,
    proxyDomain: String,
    style: GithubProxyUrlStyle = GithubProxyUrlStyle.WITH_SCHEME,
): List<UpdateDownload> {
    val parsed = mutableListOf<UpdateDownload>()
    for (itemMatch in ASSET_LIST_ITEM_REGEX.findAll(html)) {
        val block = itemMatch.value
        val href = APK_HREF_REGEX.find(block)?.groupValues?.getOrNull(1) ?: continue
        val rawName = href.substringAfterLast('/')
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        val size = ASSET_SIZE_REGEX.find(block)?.value.orEmpty()
        parsed.add(
            UpdateDownload(
                name = name,
                url = applyGithubProxy("$GITHUB_ORIGIN$href", proxyDomain, style),
                size = size,
            )
        )
    }
    if (parsed.isEmpty()) {
        for (hrefMatch in APK_HREF_REGEX.findAll(html)) {
            val href = hrefMatch.groupValues[1]
            val rawName = href.substringAfterLast('/')
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            parsed.add(
                UpdateDownload(
                    name = name,
                    url = applyGithubProxy("$GITHUB_ORIGIN$href", proxyDomain, style),
                    size = "",
                )
            )
        }
    }
    val unique = parsed.distinctBy { it.name }
    return unique.filter { it.name.contains("universal", ignoreCase = true) }.ifEmpty { unique }
}

private fun defaultUniversalDownload(
    tag: String,
    proxyDomain: String,
    style: GithubProxyUrlStyle,
) = UpdateDownload(
    name = DEFAULT_UNIVERSAL_APK_NAME,
    url = applyGithubProxy(
        "$GITHUB_ORIGIN/$REPO_PATH/releases/download/$tag/$DEFAULT_UNIVERSAL_APK_NAME",
        proxyDomain,
        style,
    ),
    size = "",
)

private fun htmlToSimpleMarkdown(html: String): String {
    var text = html
    text = HEADING_REGEX.replace(text) { match ->
        val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 2
        val inner = stripTagsAndDecodeEntities(match.groupValues[2]).trim()
        "\n${"#".repeat(level)} $inner\n\n"
    }
    text = ANCHOR_REGEX.replace(text) { match ->
        val href = decodeHtmlEntities(match.groupValues[1].trim())
        val label = stripTagsAndDecodeEntities(match.groupValues[2]).trim()
        if (label.isEmpty() || href.isEmpty()) label else "[$label]($href)"
    }
    text = INLINE_CODE_REGEX.replace(text) { match ->
        "`${stripTagsAndDecodeEntities(match.groupValues[1])}`"
    }
    text = LIST_ITEM_OPEN_REGEX.replace(text, "- ")
    text = LIST_ITEM_CLOSE_REGEX.replace(text, "\n")
    text = LINE_BREAK_REGEX.replace(text, "\n")
    text = PARAGRAPH_CLOSE_REGEX.replace(text, "\n\n")
    text = stripTagsAndDecodeEntities(text)
    val normalizedLines = text.lines().joinToString("\n") { it.trim() }
    return EXCESS_NEWLINES_REGEX.replace(normalizedLines, "\n\n").trim()
}

private fun stripTagsAndDecodeEntities(input: String): String =
    decodeHtmlEntities(HTML_TAG_REGEX.replace(input, ""))

private fun decodeHtmlEntities(input: String): String {
    val withNamed = input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
    return NUMERIC_ENTITY_REGEX.replace(withNamed) { match ->
        val codeStr = match.groupValues[1]
        val codePoint = if (codeStr.startsWith("x", ignoreCase = true)) {
            codeStr.substring(1).toIntOrNull(16)
        } else {
            codeStr.toIntOrNull(10)
        }
        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
}

private fun isValidInstant(value: String): Boolean {
    if (value.isBlank()) return false
    return runCatching { Instant.parse(value) }.isSuccess
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
