package me.rerere.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DeepSeekHashV1Test {
    @Test
    fun `solves verified real DeepSeekHashV1 vector`() {
        val salt = "3bde54628ea8413fee87"
        val expireAt = 1775380966945L
        val difficulty = 144_000L
        val challenge = "7ffc9d19b6eed96a6fca68f8ffe30ee61035d4959e4180f187bf85b356016a96"

        // Direct hash verification for the known answer
        val directHex = DeepSeekHashV1.digestHex("${salt}_${expireAt}_107544".encodeToByteArray())
        assertEquals(challenge, directHex)

        // Full search loop verification
        val startNs = System.nanoTime()
        val answer = DeepSeekHashV1.solve(
            challenge = challenge,
            salt = salt,
            expireAt = expireAt,
            difficulty = difficulty,
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertEquals(107_544L, answer)
        assertTrue("PoW solve took unexpectedly long: ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    @Test
    fun `handles multi-block inputs longer than 136 bytes`() {
        val longSalt = "s".repeat(150)
        val expireAt = 1775380966945L
        val expectedNonce = 42L
        val expectedChallenge = DeepSeekHashV1.digestHex("${longSalt}_${expireAt}_$expectedNonce".encodeToByteArray())

        val solved = DeepSeekHashV1.solve(
            challenge = expectedChallenge,
            salt = longSalt,
            expireAt = expireAt,
            difficulty = 100L,
        )
        assertEquals(expectedNonce, solved)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `builds compact Base64 X-Ds-Pow-Response header`() {
        val header = DeepSeekHashV1.buildPowResponseHeader(
            challenge = "7ffc9d19b6eed96a6fca68f8ffe30ee61035d4959e4180f187bf85b356016a96",
            salt = "3bde54628ea8413fee87",
            answer = 107_544L,
            signature = "ce4678cf7a1290c2a7ac88c4195a5b8497e5fc4b0e8044e804f5a6f3af6fe462",
            targetPath = "/api/v0/chat/completion",
        )
        assertFalse(header.contains("\n"))

        val decoded = Base64.Default.decode(header).decodeToString()
        assertFalse(decoded.contains(" "))
        val obj = SearchService.json.parseToJsonElement(decoded) as JsonObject
        assertEquals("DeepSeekHashV1", obj["algorithm"]?.jsonPrimitive?.content)
        assertEquals("3bde54628ea8413fee87", obj["salt"]?.jsonPrimitive?.content)
        assertEquals(107_544L, obj["answer"]?.jsonPrimitive?.long)
        assertEquals("/api/v0/chat/completion", obj["target_path"]?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalStateException::class)
    fun `throws when no nonce matches within difficulty`() {
        DeepSeekHashV1.solve(
            challenge = "0".repeat(64),
            salt = "3bde54628ea8413fee87",
            expireAt = 1775380966945L,
            difficulty = 16L,
        )
    }
}
