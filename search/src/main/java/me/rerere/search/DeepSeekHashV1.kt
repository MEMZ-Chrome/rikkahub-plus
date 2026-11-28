package me.rerere.search

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure Kotlin implementation of DeepSeek's `DeepSeekHashV1` PoW solver.
 *
 * `DeepSeekHashV1` is SHA3-256 (Keccak-f[1600], rate = 136 bytes, SHA3 domain separator `0x06`,
 * final block byte `|= 0x80`) executing only rounds `1..23` (23 rounds total, skipping round 0).
 *
 * All buffers are reused across nonce iterations to avoid per-iteration allocations on Android ART.
 */
internal object DeepSeekHashV1 {
    const val ALGORITHM = "DeepSeekHashV1"
    private const val RATE_BYTES = 136
    private const val RATE_LANES = RATE_BYTES / 8 // 17
    private const val CANCEL_CHECK_MASK = 0xFFF // Check cancellation every 4096 nonces

    private val RC = longArrayOf(
        0x0000000000000001UL.toLong(),
        0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(),
        0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(),
        0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(),
        0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(),
        0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(),
        0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(),
        0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(),
        0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008008UL.toLong(),
    )

    private val PI_LANE = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16,
        8, 21, 24, 4, 15, 23, 19, 13,
        12, 2, 20, 14, 22, 9, 6, 1,
    )

    private val RHO_ROT = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36,
        45, 55, 2, 14, 27, 41, 56, 8,
        25, 43, 62, 18, 39, 61, 20, 44,
    )

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )

    /**
     * Solves a DeepSeekHashV1 PoW challenge by searching `nonce` in `0..difficulty`
     * until `hash("{salt}_{expireAt}_{nonce}") == challenge`.
     */
    fun solve(
        challenge: String,
        salt: String,
        expireAt: Long,
        difficulty: Long,
        checkCancellation: () -> Unit = {},
    ): Long {
        require(difficulty >= 0L) { "Invalid PoW difficulty: $difficulty" }
        val target = parseChallengeToLanes(challenge)
        val t0 = target[0]
        val t1 = target[1]
        val t2 = target[2]
        val t3 = target[3]

        val prefixBytes = "${salt}_${expireAt}_".encodeToByteArray()
        val prefixLen = prefixBytes.size
        // Reserve room for prefix + up to 20 decimal digits of Long
        val inputBuf = ByteArray(prefixLen + 20)
        prefixBytes.copyInto(inputBuf, destinationOffset = 0)

        val state = LongArray(25)
        var nonce = 0L
        while (nonce <= difficulty) {
            if ((nonce.toInt() and CANCEL_CHECK_MASK) == 0) {
                checkCancellation()
            }
            val totalLen = writeDecimalLong(inputBuf, prefixLen, nonce)
            hashIntoState(inputBuf, totalLen, state)
            if (state[0] == t0 && state[1] == t1 && state[2] == t2 && state[3] == t3) {
                return nonce
            }
            nonce++
        }
        error("DeepSeek PoW 求解失败：在 difficulty=$difficulty 范围内未找到解")
    }

    /**
     * Computes the lowercase 64-char hex digest of [input] using DeepSeekHashV1 (23-round SHA3-256).
     */
    fun digestHex(input: ByteArray): String {
        val state = LongArray(25)
        hashIntoState(input, input.size, state)
        val out = CharArray(64)
        var outIdx = 0
        for (laneIdx in 0 until 4) {
            var lane = state[laneIdx]
            for (b in 0 until 8) {
                val byteVal = (lane and 0xFFL).toInt()
                out[outIdx++] = HEX_DIGITS[byteVal ushr 4]
                out[outIdx++] = HEX_DIGITS[byteVal and 0x0F]
                lane = lane ushr 8
            }
        }
        return String(out)
    }

    /**
     * Builds the Base64-encoded compact JSON header value for `X-Ds-Pow-Response`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun buildPowResponseHeader(
        challenge: String,
        salt: String,
        answer: Long,
        signature: String,
        targetPath: String,
        algorithm: String = ALGORITHM,
    ): String {
        val compactJson = buildJsonObject {
            put("algorithm", algorithm)
            put("challenge", challenge)
            put("salt", salt)
            put("answer", answer)
            put("signature", signature)
            put("target_path", targetPath)
        }.toString()
        return Base64.Default.encode(compactJson.encodeToByteArray())
    }

    private fun hashIntoState(input: ByteArray, length: Int, state: LongArray) {
        state.fill(0L)
        var offset = 0
        while (length - offset >= RATE_BYTES) {
            var lane = 0
            while (lane < RATE_LANES) {
                state[lane] = state[lane] xor readLittleEndianLong(input, offset + (lane shl 3))
                lane++
            }
            keccakF1600SkipRound0(state)
            offset += RATE_BYTES
        }

        val remaining = length - offset
        val fullLanes = remaining ushr 3
        var lane = 0
        while (lane < fullLanes) {
            state[lane] = state[lane] xor readLittleEndianLong(input, offset + (lane shl 3))
            lane++
        }

        val tailOffset = offset + (fullLanes shl 3)
        val tailBytes = remaining and 7
        var lastLane = 0L
        var i = 0
        while (i < tailBytes) {
            lastLane = lastLane or ((input[tailOffset + i].toLong() and 0xFFL) shl (i shl 3))
            i++
        }
        // SHA3 domain separator 0x06 right after message bytes
        lastLane = lastLane or (0x06L shl (tailBytes shl 3))
        state[fullLanes] = state[fullLanes] xor lastLane
        // Final padding bit (0x80 at byte index 135 == MSB of lane 16)
        state[RATE_LANES - 1] = state[RATE_LANES - 1] xor Long.MIN_VALUE

        keccakF1600SkipRound0(state)
    }

    private fun keccakF1600SkipRound0(a: LongArray) {
        var round = 1
        while (round < 24) {
            // Theta
            val c0 = a[0] xor a[5] xor a[10] xor a[15] xor a[20]
            val c1 = a[1] xor a[6] xor a[11] xor a[16] xor a[21]
            val c2 = a[2] xor a[7] xor a[12] xor a[17] xor a[22]
            val c3 = a[3] xor a[8] xor a[13] xor a[18] xor a[23]
            val c4 = a[4] xor a[9] xor a[14] xor a[19] xor a[24]

            val d0 = c4 xor c1.rotateLeft(1)
            val d1 = c0 xor c2.rotateLeft(1)
            val d2 = c1 xor c3.rotateLeft(1)
            val d3 = c2 xor c4.rotateLeft(1)
            val d4 = c3 xor c0.rotateLeft(1)

            a[0] = a[0] xor d0
            a[5] = a[5] xor d0
            a[10] = a[10] xor d0
            a[15] = a[15] xor d0
            a[20] = a[20] xor d0

            a[1] = a[1] xor d1
            a[6] = a[6] xor d1
            a[11] = a[11] xor d1
            a[16] = a[16] xor d1
            a[21] = a[21] xor d1

            a[2] = a[2] xor d2
            a[7] = a[7] xor d2
            a[12] = a[12] xor d2
            a[17] = a[17] xor d2
            a[22] = a[22] xor d2

            a[3] = a[3] xor d3
            a[8] = a[8] xor d3
            a[13] = a[13] xor d3
            a[18] = a[18] xor d3
            a[23] = a[23] xor d3

            a[4] = a[4] xor d4
            a[9] = a[9] xor d4
            a[14] = a[14] xor d4
            a[19] = a[19] xor d4
            a[24] = a[24] xor d4

            // Rho and Pi
            var current = a[1]
            var i = 0
            while (i < 24) {
                val dest = PI_LANE[i]
                val next = a[dest]
                a[dest] = current.rotateLeft(RHO_ROT[i])
                current = next
                i++
            }

            // Chi
            var row = 0
            while (row < 25) {
                val b0 = a[row]
                val b1 = a[row + 1]
                val b2 = a[row + 2]
                val b3 = a[row + 3]
                val b4 = a[row + 4]
                a[row] = b0 xor (b1.inv() and b2)
                a[row + 1] = b1 xor (b2.inv() and b3)
                a[row + 2] = b2 xor (b3.inv() and b4)
                a[row + 3] = b3 xor (b4.inv() and b0)
                a[row + 4] = b4 xor (b0.inv() and b1)
                row += 5
            }

            // Iota
            a[0] = a[0] xor RC[round]
            round++
        }
    }

    private fun readLittleEndianLong(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 4].toLong() and 0xFFL) shl 32) or
            ((buf[offset + 5].toLong() and 0xFFL) shl 40) or
            ((buf[offset + 6].toLong() and 0xFFL) shl 48) or
            ((buf[offset + 7].toLong() and 0xFFL) shl 56)
    }

    /**
     * Writes a non-negative [value] in ASCII decimal at [buf][[offset]..] without allocating.
     * Returns the new total length.
     */
    private fun writeDecimalLong(buf: ByteArray, offset: Int, value: Long): Int {
        if (value == 0L) {
            buf[offset] = '0'.code.toByte()
            return offset + 1
        }
        var v = value
        var pos = offset
        while (v > 0L) {
            val digit = (v % 10L).toInt()
            buf[pos++] = ('0'.code + digit).toByte()
            v /= 10L
        }
        // Reverse written digits in-place
        var left = offset
        var right = pos - 1
        while (left < right) {
            val tmp = buf[left]
            buf[left] = buf[right]
            buf[right] = tmp
            left++
            right--
        }
        return pos
    }

    private fun parseChallengeToLanes(challengeHex: String): LongArray {
        val hex = challengeHex.trim()
        require(hex.length == 64) { "Invalid DeepSeekHashV1 challenge length: ${hex.length}" }
        val lanes = LongArray(4)
        for (laneIdx in 0 until 4) {
            var lane = 0L
            for (b in 0 until 8) {
                val charIdx = (laneIdx * 8 + b) * 2
                val hi = hexNibble(hex[charIdx])
                val lo = hexNibble(hex[charIdx + 1])
                val byteVal = ((hi shl 4) or lo).toLong()
                lane = lane or (byteVal shl (b shl 3))
            }
            lanes[laneIdx] = lane
        }
        return lanes
    }

    private fun hexNibble(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex char in challenge: '$ch'")
    }
}
