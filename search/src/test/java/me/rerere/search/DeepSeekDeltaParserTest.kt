package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeepSeekDeltaParserTest {
    @Test
    fun `parses full think tool_search tool_open response batch and omitted p o continuation`() {
        val sse = """
            event: ready
            data: {"request_message_id":1,"response_message_id":42,"model_type":"default"}

            event: update_session
            data: {"updated_at":1775386361.526}

            data: {"v":{"response":{"message_id":42,"status":"WIP","fragments":[{"id":1,"type":"THINK","content":"正在分析问题..."}]}}}

            data: {"p":"response/fragments/-1/content","o":"APPEND","v":"需要搜索 Kotlin 2.4 发布信息。"}

            data: {"v":"继续思考但不应进入正文。"}

            data: {"p":"response/fragments/-1/elapsed_secs","o":"SET","v":1.25}

            data: {"p":"response/fragments","o":"APPEND","v":[{"id":2,"type":"TOOL_SEARCH","queries":[{"query":"Kotlin 2.4 release"}],"results":[{"url":"https://kotlinlang.org/docs/whatsnew24.html","title":"What's new in Kotlin 2.4","snippet":"Initial short snippet","cite_index":1}]}]}

            data: {"p":"response/fragments/-1/results","o":"APPEND","v":[{"url":"https://blog.jetbrains.com/kotlin/2026/","title":"JetBrains Kotlin Blog","snippet":"Detailed release highlights.","published_at":"2026-10-01","cite_index":2}]}

            data: {"p":"response/fragments","o":"APPEND","v":[{"id":3,"type":"TOOL_OPEN","status":"WIP"},{"id":4,"type":"TOOL_OPEN","status":"WIP"}]}

            data: {"p":"response/fragments/-2/result","o":"SET","v":{"url":"https://kotlinlang.org/docs/whatsnew24.html","title":"What's new in Kotlin 2.4","snippet":"Full opened page summary with much richer details about Kotlin 2.4 compiler improvements."}}

            data: {"p":"response/fragments/-1/result","o":"SET","v":{"url":"https://github.com/JetBrains/kotlin/releases","title":"Kotlin Releases on GitHub","snippet":"Official release artifacts and changelog."}}

            data: {"p":"response/fragments","o":"APPEND","v":[{"id":5,"type":"RESPONSE","content":"Kotlin 2.4 "}]}

            data: {"p":"response/fragments/-1/content","o":"APPEND","v":"带来了 "}

            data: {"v":"更快的编译速度"}

            data: {"v":" 与多项新特性"}

            data: {"p":"response/fragments/-1","o":"BATCH","v":[{"p":"content","o":"APPEND","v":"[reference:1][reference:2]。"},{"p":"references","o":"SET","v":[{"id":2,"type":"TOOL_SEARCH"}]}]}

            data: {"p":"response/fragments","o":"APPEND","v":[{"id":6,"type":"TIP","content":"本内容由 AI 生成，仅供参考"}]}

            data: {"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":139},{"p":"quasi_status","v":"FINISHED"}]}

            data: {"p":"response/status","o":"SET","v":"FINISHED"}

            event: title
            data: {"content":"Kotlin 2.4"}

            event: close
            data: {"click_behavior":"none","auto_resume":false}
        """.trimIndent()

        val parser = DeepSeekDeltaParser()
        parser.parseAll(sse)

        assertEquals(42L, parser.responseMessageId)
        assertTrue(parser.isDone)
        assertEquals("FINISHED", parser.status)
        assertEquals("FINISHED", parser.quasiStatus)
        assertEquals(139, parser.accumulatedTokenUsage)

        // THINK and TIP fragments must NOT leak into summary
        assertEquals(
            "Kotlin 2.4 带来了 更快的编译速度 与多项新特性[reference:1][reference:2]。",
            parser.summary,
        )
        assertFalse(parser.summary.contains("正在分析问题"))
        assertFalse(parser.summary.contains("本内容由 AI 生成"))

        // Sources must be deduplicated by URL and enriched by TOOL_OPEN
        assertEquals(3, parser.sources.size)
        assertEquals("https://kotlinlang.org/docs/whatsnew24.html", parser.sources[0].url)
        assertEquals(
            "Full opened page summary with much richer details about Kotlin 2.4 compiler improvements.",
            parser.sources[0].snippet,
        )
        assertEquals(1, parser.sources[0].citeIndex)

        assertEquals("https://blog.jetbrains.com/kotlin/2026/", parser.sources[1].url)
        assertEquals("2026-10-01", parser.sources[1].publishedDate)
        assertEquals(2, parser.sources[1].citeIndex)

        assertEquals("https://github.com/JetBrains/kotlin/releases", parser.sources[2].url)
        assertEquals("Kotlin Releases on GitHub", parser.sources[2].title)
    }

    @Test
    fun `batch sub-parser persists subPath and subOp independently from parent`() {
        val sse = """
            data: {"v":{"response":{"message_id":2,"fragments":[{"id":1,"type":"RESPONSE","content":"Start: "}]}}}

            data: {"p":"response/fragments/-1/content","o":"APPEND","v":"A"}

            data: {"p":"response","o":"BATCH","v":[{"p":"fragments/-1/content","o":"APPEND","v":"B"},{"v":"C"},{"p":"accumulated_token_usage","o":"SET","v":55}]}

            data: {"p":"response/status","o":"SET","v":"FINISHED"}
        """.trimIndent()

        val parser = DeepSeekDeltaParser()
        parser.parseAll(sse)

        assertEquals("Start: ABC", parser.summary)
        assertEquals(55, parser.accumulatedTokenUsage)
        assertTrue(parser.isDone)
    }

    @Test
    fun `flushes trailing SSE frame at EOF without final blank line`() {
        val sse = listOf(
            """data: {"v":{"response":{"message_id":7,"fragments":[{"id":1,"type":"RESPONSE","content":"Hello"}]}}}""",
            "",
            """data: {"p":"response/fragments/-1/content","o":"APPEND","v":" World"}""",
            "",
            """data: {"p":"response/status","o":"SET","v":"FINISHED"}""",
        ).joinToString("\n")

        val parser = DeepSeekDeltaParser()
        parser.parseAll(sse)

        assertEquals("Hello World", parser.summary)
        assertEquals("FINISHED", parser.status)
        assertTrue(parser.isDone)
    }

    @Test
    fun `detects plain JSON account muted error with mute_until`() {
        val mutedJson = """{"code":0,"msg":"","data":{"biz_code":5,"biz_msg":"user is muted","biz_data":{"is_muted":1,"mute_until":1790397239.972}}}"""
        val parser = DeepSeekDeltaParser()
        try {
            parser.parseAll(mutedJson)
            fail("Expected DeepSeekAccountMutedException")
        } catch (e: DeepSeekAccountMutedException) {
            assertTrue(e.message.orEmpty().contains("禁言"))
            assertTrue(e.message.orEmpty().contains("预计解封时间"))
            assertEquals(1790397239.972, e.muteUntilEpochSeconds ?: 0.0, 0.001)
        }
    }

    @Test
    fun `detects plain JSON invalid token error code 40003`() {
        val invalidTokenJson = """{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"""
        val parser = DeepSeekDeltaParser()
        try {
            parser.parseAll(invalidTokenJson)
            fail("Expected DeepSeekTokenExpiredException")
        } catch (e: DeepSeekTokenExpiredException) {
            assertTrue(e.message.orEmpty().contains("Token 已失效"))
        }
    }

    @Test
    fun `detects SSE hint rate_limit_reached error`() {
        val sse = """
            event: ready
            data: {"request_message_id":1,"response_message_id":2}

            event: hint
            data: {"type":"error","content":"消息发送过于频繁，请稍后重试","finish_reason":"rate_limit_reached"}
        """.trimIndent()

        val parser = DeepSeekDeltaParser()
        try {
            parser.parseAll(sse)
            fail("Expected DeepSeekRateLimitException")
        } catch (e: DeepSeekRateLimitException) {
            assertEquals(2L, parser.responseMessageId)
            assertTrue(e.message.orEmpty().contains("rate_limit_reached"))
            assertTrue(e.message.orEmpty().contains("频繁"))
        }
    }

    @Test
    fun `detects SSE toast error`() {
        val sse = """
            event: toast
            data: {"type":"error","content":"Client version unsupported","finish_reason":"client_outdated"}
        """.trimIndent()

        val parser = DeepSeekDeltaParser()
        try {
            parser.parseAll(sse)
            fail("Expected DeepSeekStreamErrorException")
        } catch (e: DeepSeekStreamErrorException) {
            assertEquals("client_outdated", e.finishReason)
            assertTrue(e.message.orEmpty().contains("Client version unsupported"))
        }
    }
}
