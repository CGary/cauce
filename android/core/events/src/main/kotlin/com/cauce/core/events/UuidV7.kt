package com.cauce.core.events

import java.security.SecureRandom

/**
 * UUID v7: 48-bit millisecond timestamp followed by random bits. Lexicographic order follows
 * creation time, which is why the log uses it as the event id.
 */
object UuidV7 {
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()

    fun newId(nowMillis: Long = System.currentTimeMillis()): String {
        require(nowMillis in 0 until (1L shl 48)) { "timestamp out of range for UUID v7: $nowMillis" }

        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = (nowMillis ushr 40).toByte()
        bytes[1] = (nowMillis ushr 32).toByte()
        bytes[2] = (nowMillis ushr 24).toByte()
        bytes[3] = (nowMillis ushr 16).toByte()
        bytes[4] = (nowMillis ushr 8).toByte()
        bytes[5] = nowMillis.toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

        val out = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-')
            val value = bytes[i].toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
