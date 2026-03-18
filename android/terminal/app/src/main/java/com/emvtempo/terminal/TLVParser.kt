package com.emvtempo.terminal

/**
 * BER-TLV parser for EMV response data.
 *
 * Handles single-byte and multi-byte tags, as well as definite-length
 * encoding (short and long forms) per ISO 8825-1 / EMV Book 3.
 */
object TLVParser {

    data class TLVEntry(
        val tag: String,
        val value: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TLVEntry) return false
            return tag == other.tag && value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            return 31 * tag.hashCode() + value.contentHashCode()
        }

        override fun toString(): String {
            return "TLVEntry(tag=$tag, value=${value.toHex()})"
        }
    }

    /**
     * Parse a byte array containing BER-TLV encoded data into a list of
     * [TLVEntry] objects. Nested constructed TLV objects are flattened
     * (their children are included alongside the parent).
     */
    fun parse(data: ByteArray): List<TLVEntry> {
        val entries = mutableListOf<TLVEntry>()
        var offset = 0

        while (offset < data.size) {
            // ----- Tag -----
            if (offset >= data.size) break
            val tagStart = offset
            val firstByte = data[offset].toInt() and 0xFF
            offset++

            // If low 5 bits are all 1s, tag continues in subsequent bytes
            if (firstByte and 0x1F == 0x1F) {
                while (offset < data.size) {
                    val b = data[offset].toInt() and 0xFF
                    offset++
                    if (b and 0x80 == 0) break // last byte of tag
                }
            }

            val tagBytes = data.sliceArray(tagStart until offset)
            val tagHex = tagBytes.toHex().uppercase()

            // ----- Length -----
            if (offset >= data.size) break
            val lengthByte = data[offset].toInt() and 0xFF
            offset++

            val length: Int
            if (lengthByte <= 0x7F) {
                length = lengthByte
            } else {
                val numLengthBytes = lengthByte and 0x7F
                if (numLengthBytes == 0 || numLengthBytes > 4) break // unsupported
                var len = 0
                for (i in 0 until numLengthBytes) {
                    if (offset >= data.size) break
                    len = (len shl 8) or (data[offset].toInt() and 0xFF)
                    offset++
                }
                length = len
            }

            // ----- Value -----
            if (offset + length > data.size) break
            val value = data.sliceArray(offset until offset + length)
            offset += length

            entries.add(TLVEntry(tagHex, value))

            // If constructed (bit 6 of first tag byte set), also parse children
            val isConstructed = firstByte and 0x20 != 0
            if (isConstructed && value.isNotEmpty()) {
                entries.addAll(parse(value))
            }
        }

        return entries
    }

    /**
     * Find the first [TLVEntry] matching the given hex tag string.
     * Comparison is case-insensitive.
     */
    fun findTag(entries: List<TLVEntry>, tag: String): TLVEntry? {
        val upperTag = tag.uppercase()
        return entries.firstOrNull { it.tag == upperTag }
    }
}

/** Convert a byte array to a lowercase hex string. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Convert a hex string to a byte array. */
fun String.hexToBytes(): ByteArray {
    val hex = this.replace(" ", "")
    require(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
