package me.rerere.rikkahub.service

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePromptServiceTest {
    private val service = HomePromptService(
        httpClient = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `blank script returns null`() = runTest {
        val result = service.fetch("   ")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `sync script returns single image phrase and prompt`() = runTest {
        val script = """
            function getHomePrompt() {
                return {
                    image: "https://example.com/pic.png",
                    phrase: "Hello today",
                    prompt: "Write a haiku about today"
                };
            }
        """.trimIndent()

        val item = service.fetch(script).getOrThrow()!!
        assertEquals(
            HomePromptItem(
                imageLight = "https://example.com/pic.png",
                imageDark = "",
                phrase = "Hello today",
                prompt = "Write a haiku about today",
            ),
            item,
        )
        assertEquals("https://example.com/pic.png", item.imageForTheme(darkTheme = false))
        assertEquals("https://example.com/pic.png", item.imageForTheme(darkTheme = true))
        assertFalse(item.isRiveMedia(item.imageLight))
        assertEquals("Write a haiku about today", item.insertText)
    }

    @Test
    fun `supports light and dark image pair as object or array`() = runTest {
        val objectScript = """
            function getHomePrompt() {
                return {
                    image: { light: "https://example.com/light.png", dark: "https://example.com/dark.png" },
                    welcomeMessage: "Good evening"
                };
            }
        """.trimIndent()

        val objItem = service.fetch(objectScript).getOrThrow()!!
        assertEquals("https://example.com/light.png", objItem.imageForTheme(darkTheme = false))
        assertEquals("https://example.com/dark.png", objItem.imageForTheme(darkTheme = true))
        assertEquals("Good evening", objItem.phrase)
        assertEquals("Good evening", objItem.insertText)

        val arrayScript = """
            function getHomePrompt() {
                return {
                    image: ["https://example.com/light.riv?v=2", "https://example.com/dark.riv#doodle"],
                    phrase: "Hi"
                };
            }
        """.trimIndent()

        val arrItem = service.fetch(arrayScript).getOrThrow()!!
        assertEquals("https://example.com/light.riv?v=2", arrItem.imageForTheme(darkTheme = false))
        assertEquals("https://example.com/dark.riv#doodle", arrItem.imageForTheme(darkTheme = true))
        assertTrue(arrItem.isRiveMedia(arrItem.imageForTheme(darkTheme = false)))
        assertTrue(arrItem.isRiveMedia(arrItem.imageForTheme(darkTheme = true)))
    }

    @Test
    fun `detects riv urls and explicit rive fields`() = runTest {
        assertTrue(isRiveUrl("https://cdn.example.com/doodle.riv"))
        assertTrue(isRiveUrl("https://cdn.example.com/doodle.RIV?token=abc"))
        assertFalse(isRiveUrl("https://cdn.example.com/doodle.png"))
        assertTrue(HomePromptRiveLoader.hasRiveHeader("RIVE\u0007\u0000".toByteArray()))
        assertFalse(HomePromptRiveLoader.hasRiveHeader("<html>".toByteArray()))

        val explicitScript = """
            function getHomePrompt() {
                return {
                    rive: ["https://proxy.example.com/doodle?theme=light", "https://proxy.example.com/doodle?theme=dark"],
                    phrase: "Kimi doodle"
                };
            }
        """.trimIndent()

        val item = service.fetch(explicitScript).getOrThrow()!!
        assertTrue(item.isRive)
        assertTrue(item.isRiveMedia(item.imageForTheme(darkTheme = false)))
        assertEquals("https://proxy.example.com/doodle?theme=dark", item.imageForTheme(darkTheme = true))
    }

    @Test
    fun `async script is awaited`() = runTest {
        val script = """
            async function getHomePrompt() {
                return {
                    phrase: "Async greeting"
                };
            }
        """.trimIndent()

        val item = service.fetch(script).getOrThrow()!!
        assertEquals(HomePromptItem(phrase = "Async greeting"), item)
        assertEquals("Async greeting", item.insertText)
    }

    @Test
    fun `null or empty display fields return null`() = runTest {
        assertNull(service.fetch("function getHomePrompt() { return null; }").getOrThrow())
        assertNull(service.fetch("function getHomePrompt() { return {}; }").getOrThrow())
        assertNull(
            service.fetch("""function getHomePrompt() { return { image: " ", phrase: "", prompt: "only" }; }""")
                .getOrThrow()
        )
    }

    @Test
    fun `missing getHomePrompt function fails`() = runTest {
        val result = service.fetch("function other() { return {}; }")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("getHomePrompt"))
    }
}
