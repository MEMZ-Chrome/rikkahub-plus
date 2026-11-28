package me.rerere.rikkahub.utils

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class UpdateCheckerTest {

    @Test
    fun `normalizes valid update proxy domains`() {
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("ghfast.top"))
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("https://ghfast.top"))
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("https://ghfast.top/"))
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("https://ghfast.top/https://"))
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("https://ghfast.top/HTTP://"))
        assertEquals("https://ghfast.top", normalizeUpdateProxyDomain("ghfast.top/https://"))
        assertEquals(
            "https://proxy.example.com/gh",
            normalizeUpdateProxyDomain("https://proxy.example.com/gh/"),
        )
        assertEquals("http://127.0.0.1:8080", normalizeUpdateProxyDomain("http://127.0.0.1:8080/"))
        assertEquals("http://localhost:8080", normalizeUpdateProxyDomain("http://localhost:8080"))
    }

    @Test
    fun `rejects invalid update proxy domains`() {
        assertNull(normalizeUpdateProxyDomain(""))
        assertNull(normalizeUpdateProxyDomain("   "))
        assertNull(normalizeUpdateProxyDomain("https://"))
        assertNull(normalizeUpdateProxyDomain("http://"))
        assertNull(normalizeUpdateProxyDomain("http"))
        assertNull(normalizeUpdateProxyDomain("ftp://ghfast.top"))
        assertNull(normalizeUpdateProxyDomain("ghfast top"))
        assertNull(normalizeUpdateProxyDomain("https://ghfast.top?foo=bar"))
        assertNull(normalizeUpdateProxyDomain("https://user:pass@ghfast.top"))
    }

    @Test
    fun `applies github proxy prefix in both styles and avoids double prefix`() {
        val target = "https://github.com/MEMZ-Chrome/rikkahub-plus/releases/latest"

        assertEquals(target, applyGithubProxy(target, ""))
        assertEquals(
            "https://ghfast.top/$target",
            applyGithubProxy(target, "ghfast.top"),
        )
        assertEquals(
            "https://ghfast.top/$target",
            applyGithubProxy(target, "https://ghfast.top/https://"),
        )
        assertEquals(
            "https://ghfast.top/github.com/MEMZ-Chrome/rikkahub-plus/releases/latest",
            applyGithubProxy(target, "https://ghfast.top/", GithubProxyUrlStyle.WITHOUT_SCHEME),
        )
        assertEquals(
            "https://ghfast.top/$target",
            applyGithubProxy("https://ghfast.top/$target", "https://ghfast.top/"),
        )
    }

    @Test
    fun `parses release tag, timestamp, changelog, and expanded assets from html`() {
        assertEquals(
            "v3.3.1-plus.6",
            extractReleaseTagFromUrl("https://github.com/MEMZ-Chrome/rikkahub-plus/releases/tag/v3.3.1-plus.6"),
        )
        assertEquals(
            "v3.3.1-plus.6",
            extractReleaseTagFromUrl("/MEMZ-Chrome/rikkahub-plus/releases/tag/v3.3.1-plus.6?ref=1"),
        )
        assertTrue(isRepoReleasesIndexUrl("https://github.com/MEMZ-Chrome/rikkahub-plus/releases"))
        assertFalse(isRepoReleasesIndexUrl("https://github.com/MEMZ-Chrome/rikkahub-plus/releases/tag/v1.0.0"))

        val releaseHtml = """
            <div>
              <relative-time datetime="not-an-iso-timestamp">bad</relative-time>
              <relative-time class="no-wrap" datetime="2026-11-28T02:46:11Z">28 Nov</relative-time>
              <div data-test-selector="body-content" class="markdown-body tmp-my-3">
                <h2>新增内容</h2>
                <ul>
                  <li>feat: support <code>updateProxyDomain</code> &amp; tests</li>
                  <li>fix: <a href="https://example.com">link</a></li>
                </ul>
              </div>
            </div>
        """.trimIndent()

        val publishedAt = parseReleasePublishedAtFromHtml(releaseHtml)
        assertEquals("2026-11-28T02:46:11Z", publishedAt)
        Instant.parse(publishedAt!!)

        val changelog = parseReleaseChangelogFromHtml(releaseHtml)
        assertTrue(changelog.contains("## 新增内容"))
        assertTrue(changelog.contains("- feat: support `updateProxyDomain` & tests"))
        assertTrue(changelog.contains("- fix: [link](https://example.com)"))

        val assetsHtml = """
            <ul>
              <li>
                <a href="/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.6/app-github-arm64-v8a-release.apk">arm64</a>
                <span>18.1 MB</span>
              </li>
              <li>
                <a href="/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.6/app-github-universal-release.apk">universal</a>
                <span>23.6 MB</span>
              </li>
            </ul>
        """.trimIndent()

        val downloads = parseExpandedAssetsFromHtml(assetsHtml, "https://ghfast.top/")
        assertEquals(1, downloads.size)
        assertEquals("app-github-universal-release.apk", downloads.first().name)
        assertEquals("23.6 MB", downloads.first().size)
        assertEquals(
            "https://ghfast.top/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.6/app-github-universal-release.apk",
            downloads.first().url,
        )
    }

    @Test
    fun `direct mode fetches github api directly when proxy domain is blank`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = fakeClient { request ->
            requestedUrls.add(request.url.toString())
            jsonResponse(
                request,
                200,
                """
                {
                  "tag_name": "v3.3.1-plus.7",
                  "published_at": "2026-11-28T10:00:00Z",
                  "body": "## Changes",
                  "assets": [
                    {
                      "name": "app-github-universal-release.apk",
                      "browser_download_url": "https://github.com/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.7/app-github-universal-release.apk",
                      "size": 24746394
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val fetcher = UpdateReleaseFetcher(client = client, currentVersion = "3.3.1-plus.6", userAgent = "Test")
        val info = fetcher.fetchUpdateInfo("")

        assertEquals(
            listOf("https://api.github.com/repos/MEMZ-Chrome/rikkahub-plus/releases/latest"),
            requestedUrls,
        )
        assertEquals("3.3.1-plus.7", info.version)
        assertEquals("2026-11-28T10:00:00Z", info.publishedAt)
        assertEquals("## Changes", info.changelog)
        assertEquals(
            "https://github.com/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.7/app-github-universal-release.apk",
            info.downloads.single().url,
        )
    }

    @Test
    fun `proxied mode never hits github directly and handles 302 redirect via proxy`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = fakeClient { request ->
            val url = request.url.toString()
            requestedUrls.add(url)
            when (url) {
                "https://ghfast.top/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/latest" ->
                    redirectResponse(
                        request,
                        "https://github.com/MEMZ-Chrome/rikkahub-plus/releases/tag/v3.3.1-plus.7",
                    )
                "https://ghfast.top/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/tag/v3.3.1-plus.7" ->
                    htmlResponse(
                        request,
                        200,
                        """
                        <relative-time datetime="2026-11-28T10:00:00Z"></relative-time>
                        <div class="markdown-body"><h2>Release</h2><ul><li>Proxy support</li></ul></div>
                        """.trimIndent(),
                    )
                "https://ghfast.top/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/expanded_assets/v3.3.1-plus.7" ->
                    htmlResponse(
                        request,
                        200,
                        """
                        <li>
                          <a href="/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.7/app-github-universal-release.apk">universal</a>
                          <span>23.6 MB</span>
                        </li>
                        """.trimIndent(),
                    )
                else -> htmlResponse(request, 404, "not found")
            }
        }

        val fetcher = UpdateReleaseFetcher(client = client, currentVersion = "3.3.1-plus.6", userAgent = "Test")
        val info = fetcher.fetchUpdateInfo("ghfast.top")

        assertTrue(requestedUrls.all { it.startsWith("https://ghfast.top/") })
        assertEquals("3.3.1-plus.7", info.version)
        assertEquals("2026-11-28T10:00:00Z", info.publishedAt)
        assertTrue(info.changelog.contains("Proxy support"))
        assertEquals(
            "https://ghfast.top/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.7/app-github-universal-release.apk",
            info.downloads.single().url,
        )
        assertEquals("23.6 MB", info.downloads.single().size)
    }

    @Test
    fun `proxied mode falls back to without-scheme prefix and default apk when metadata endpoints fail`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = fakeClient { request ->
            val url = request.url.toString()
            requestedUrls.add(url)
            when (url) {
                "https://mirror.example.com/https://github.com/MEMZ-Chrome/rikkahub-plus/releases/latest" ->
                    htmlResponse(request, 403, "unsupported prefix")
                "https://mirror.example.com/github.com/MEMZ-Chrome/rikkahub-plus/releases/latest" ->
                    jsonResponse(request, 200, """{"id": 1, "tag_name": "v3.3.1-plus.8"}""")
                else -> htmlResponse(request, 404, "blocked")
            }
        }

        val fetcher = UpdateReleaseFetcher(client = client, currentVersion = "3.3.1-plus.6", userAgent = "Test")
        val info = fetcher.fetchUpdateInfo("https://mirror.example.com/")

        assertTrue(requestedUrls.all { it.startsWith("https://mirror.example.com/") })
        assertEquals("3.3.1-plus.8", info.version)
        assertEquals("1970-01-01T00:00:00Z", info.publishedAt)
        Instant.parse(info.publishedAt)
        assertEquals(
            "https://mirror.example.com/github.com/MEMZ-Chrome/rikkahub-plus/releases/download/v3.3.1-plus.8/app-github-universal-release.apk",
            info.downloads.single().url,
        )
    }

    @Test
    fun `proxied mode treats redirect to releases index as no update`() = runTest {
        val client = fakeClient { request ->
            redirectResponse(request, "https://github.com/MEMZ-Chrome/rikkahub-plus/releases")
        }

        val fetcher = UpdateReleaseFetcher(client = client, currentVersion = "3.3.1-plus.6", userAgent = "Test")
        val info = fetcher.fetchUpdateInfo("https://ghfast.top/")

        assertEquals("3.3.1-plus.6", info.version)
        assertTrue(info.downloads.isEmpty())
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun htmlResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()

    private fun redirectResponse(request: Request, location: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("Location", location)
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
}
