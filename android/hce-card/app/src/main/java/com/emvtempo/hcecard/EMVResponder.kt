package com.emvtempo.hcecard

/**
 * BER-TLV encoder utilities for building EMV APDU responses.
 *
 * Mirrors the TLV encoding logic from the TypeScript simulator (tlv.ts)
 * but implemented in pure Kotlin for the Android HCE service.
 */
object EMVResponder {

    /**
     * Encode a single BER-TLV: tag || length || value.
     * Supports 1-byte and 2-byte tags, and BER-TLV length encoding.
     */
    fun encodeTLV(tag: String, value: ByteArray): ByteArray {
        val tagBytes = hexToBytes(tag)
        val lenBytes = encodeLength(value.size)
        return tagBytes + lenBytes + value
    }

    /**
     * Wrap inner data inside a constructed TLV tag (e.g., 6F, 77, 70).
     * Equivalent to encodeTLV(tag, innerData).
     */
    fun wrapTLV(tag: String, innerData: ByteArray): ByteArray {
        return encodeTLV(tag, innerData)
    }

    /**
     * Encode multiple TLV entries and concatenate them.
     */
    fun encodeTLVList(entries: List<Pair<String, ByteArray>>): ByteArray {
        return entries.fold(byteArrayOf()) { acc, (tag, value) ->
            acc + encodeTLV(tag, value)
        }
    }

    /**
     * Encode a BER-TLV length field.
     * - 0x00..0x7F  -> 1 byte
     * - 0x80..0xFF  -> 0x81 + 1 byte
     * - 0x100+      -> 0x82 + 2 bytes
     */
    private fun encodeLength(len: Int): ByteArray {
        return when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(
                0x82.toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
    }

    /**
     * Concatenate two byte arrays.
     */
    fun concat(a: ByteArray, b: ByteArray): ByteArray = a + b

    /**
     * Convert a hex string (e.g. "9F02") to a ByteArray.
     */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("\\s".toRegex(), "")
        require(clean.length % 2 == 0) { "Hex string must have even length: $clean" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Convert a ByteArray to a lowercase hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
