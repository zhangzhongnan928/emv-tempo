package com.emvtempo.terminal

import android.nfc.tech.IsoDep
import android.util.Log
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EMV contactless flow driver.
 *
 * Builds APDU commands, drives the SELECT -> GPO -> READ RECORD -> GENERATE AC
 * sequence over IsoDep, and extracts the data needed for on-chain settlement.
 */
object EMVDriver {

    private const val TAG = "EMVDriver"

    /** AID for the PoC applet. */
    private val AID = "A0000009510001".hexToBytes()

    /** Currency code for AUD (0x0036). */
    private const val CURRENCY_AUD = 0x0036

    /** Country code for Australia (0x0036). */
    private const val COUNTRY_AU = 0x0036

    // -------------------------------------------------------------------------
    // Data class returned after a successful EMV flow
    // -------------------------------------------------------------------------

    data class EMVResult(
        val cdol1Data: ByteArray,
        val pan: ByteArray,
        val sigR: ByteArray,
        val sigS: ByteArray,
        val pubKeyX: ByteArray,
        val pubKeyY: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EMVResult) return false
            return cdol1Data.contentEquals(other.cdol1Data) &&
                pan.contentEquals(other.pan) &&
                sigR.contentEquals(other.sigR) &&
                sigS.contentEquals(other.sigS) &&
                pubKeyX.contentEquals(other.pubKeyX) &&
                pubKeyY.contentEquals(other.pubKeyY)
        }

        override fun hashCode(): Int {
            var result = cdol1Data.contentHashCode()
            result = 31 * result + pan.contentHashCode()
            result = 31 * result + sigR.contentHashCode()
            result = 31 * result + sigS.contentHashCode()
            result = 31 * result + pubKeyX.contentHashCode()
            result = 31 * result + pubKeyY.contentHashCode()
            return result
        }
    }

    // -------------------------------------------------------------------------
    // APDU builders
    // -------------------------------------------------------------------------

    /**
     * Build a SELECT APDU: 00 A4 04 00 [Lc] [AID] 00
     */
    fun buildSelectApdu(aid: ByteArray): ByteArray {
        val apdu = ByteArray(6 + aid.size)
        apdu[0] = 0x00.toByte() // CLA
        apdu[1] = 0xA4.toByte() // INS
        apdu[2] = 0x04.toByte() // P1 - select by name
        apdu[3] = 0x00.toByte() // P2
        apdu[4] = aid.size.toByte() // Lc
        System.arraycopy(aid, 0, apdu, 5, aid.size)
        apdu[5 + aid.size] = 0x00.toByte() // Le
        return apdu
    }

    /**
     * Build a GET PROCESSING OPTIONS APDU: 80 A8 00 00 [Lc] 83 [len] [PDOL data] 00
     */
    fun buildGPOApdu(pdolData: ByteArray): ByteArray {
        // Data field = 83 [length] [pdolData]
        val dataField = ByteArray(2 + pdolData.size)
        dataField[0] = 0x83.toByte()
        dataField[1] = pdolData.size.toByte()
        System.arraycopy(pdolData, 0, dataField, 2, pdolData.size)

        val apdu = ByteArray(5 + dataField.size + 1)
        apdu[0] = 0x80.toByte() // CLA
        apdu[1] = 0xA8.toByte() // INS
        apdu[2] = 0x00.toByte() // P1
        apdu[3] = 0x00.toByte() // P2
        apdu[4] = dataField.size.toByte() // Lc
        System.arraycopy(dataField, 0, apdu, 5, dataField.size)
        apdu[5 + dataField.size] = 0x00.toByte() // Le
        return apdu
    }

    /**
     * Build a READ RECORD APDU: 00 B2 [record] [SFI<<3 | 0x04] 00
     */
    fun buildReadRecordApdu(record: Int, sfi: Int): ByteArray {
        return byteArrayOf(
            0x00.toByte(), // CLA
            0xB2.toByte(), // INS
            record.toByte(), // P1 - record number
            ((sfi shl 3) or 0x04).toByte(), // P2 - SFI + reference control
            0x00.toByte() // Le
        )
    }

    /**
     * Build a GENERATE AC APDU: 80 AE 80 00 [Lc] [CDOL1 data] 00
     *
     * P1 = 0x80 requests a TC (Transaction Certificate) for online authorization.
     */
    fun buildGenerateACApdu(cdol1Data: ByteArray): ByteArray {
        val apdu = ByteArray(5 + cdol1Data.size + 1)
        apdu[0] = 0x80.toByte() // CLA
        apdu[1] = 0xAE.toByte() // INS
        apdu[2] = 0x80.toByte() // P1 - request TC
        apdu[3] = 0x00.toByte() // P2
        apdu[4] = cdol1Data.size.toByte() // Lc
        System.arraycopy(cdol1Data, 0, apdu, 5, cdol1Data.size)
        apdu[5 + cdol1Data.size] = 0x00.toByte() // Le
        return apdu
    }

    // -------------------------------------------------------------------------
    // PDOL data construction
    // -------------------------------------------------------------------------

    /**
     * Build PDOL data (16 bytes) as requested by our card's PDOL:
     *   TTQ (9F66)           4 bytes
     *   Amount Auth (9F02)   6 bytes BCD
     *   Currency (5F2A)      2 bytes
     *   Unpredictable# (9F37) 4 bytes
     */
    fun buildPDOLData(amountCents: Int, un: ByteArray): ByteArray {
        require(un.size == 4) { "Unpredictable Number must be 4 bytes" }
        val data = ByteArray(16)
        // TTQ: basic contactless
        data[0] = 0x36.toByte()
        data[1] = 0x00
        data[2] = 0x00
        data[3] = 0x00
        // Amount (6 bytes BCD)
        System.arraycopy(amountToBCD(amountCents), 0, data, 4, 6)
        // Currency (2 bytes)
        data[10] = (CURRENCY_AUD shr 8).toByte()
        data[11] = (CURRENCY_AUD and 0xFF).toByte()
        // UN (4 bytes)
        System.arraycopy(un, 0, data, 12, 4)
        return data
    }

    // -------------------------------------------------------------------------
    // CDOL1 data construction
    // -------------------------------------------------------------------------

    /**
     * Build CDOL1 data (29 bytes):
     *   Amount Authorized     6 bytes (BCD)
     *   Amount Other          6 bytes (zeros)
     *   Terminal Country Code 2 bytes
     *   TVR                   5 bytes (zeros for PoC)
     *   Transaction Currency  2 bytes
     *   Transaction Date      3 bytes (YYMMDD BCD)
     *   Transaction Type      1 byte  (0x00 = purchase)
     *   Unpredictable Number  4 bytes
     */
    fun buildCDOL1Data(
        amountCents: Int,
        currencyCode: Int = CURRENCY_AUD,
        countryCode: Int = COUNTRY_AU,
        un: ByteArray
    ): ByteArray {
        require(un.size == 4) { "Unpredictable Number must be 4 bytes" }

        val data = ByteArray(29)
        var offset = 0

        // Amount Authorized (6 bytes BCD)
        val amountBcd = amountToBCD(amountCents)
        System.arraycopy(amountBcd, 0, data, offset, 6)
        offset += 6

        // Amount Other (6 bytes, zero)
        offset += 6

        // Terminal Country Code (2 bytes)
        data[offset] = (countryCode shr 8).toByte()
        data[offset + 1] = (countryCode and 0xFF).toByte()
        offset += 2

        // TVR (5 bytes, zero for PoC)
        offset += 5

        // Transaction Currency Code (2 bytes)
        data[offset] = (currencyCode shr 8).toByte()
        data[offset + 1] = (currencyCode and 0xFF).toByte()
        offset += 2

        // Transaction Date (3 bytes, YYMMDD in BCD)
        val dateFormat = SimpleDateFormat("yyMMdd", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val dateStr = dateFormat.format(Date())
        val dateBytes = dateStr.hexToBytes()
        System.arraycopy(dateBytes, 0, data, offset, 3)
        offset += 3

        // Transaction Type (1 byte, 0x00 = purchase)
        data[offset] = 0x00.toByte()
        offset += 1

        // Unpredictable Number (4 bytes)
        System.arraycopy(un, 0, data, offset, 4)

        return data
    }

    /**
     * Convert an amount in cents to a 6-byte BCD representation.
     * Example: 1050 (= $10.50) -> 00 00 00 00 10 50
     */
    fun amountToBCD(cents: Int): ByteArray {
        require(cents >= 0) { "Amount must be non-negative" }
        val bcd = ByteArray(6)
        val str = "%012d".format(cents) // 12 digits -> 6 bytes
        for (i in 0 until 6) {
            val hi = str[i * 2] - '0'
            val lo = str[i * 2 + 1] - '0'
            bcd[i] = ((hi shl 4) or lo).toByte()
        }
        return bcd
    }

    // -------------------------------------------------------------------------
    // Full EMV contactless flow
    // -------------------------------------------------------------------------

    /**
     * Run the complete EMV contactless flow over an [IsoDep] connection:
     *   1. SELECT AID
     *   2. GET PROCESSING OPTIONS
     *   3. READ RECORD (iterate AFL entries)
     *   4. GENERATE AC with CDOL1
     *
     * Returns an [EMVResult] with all data needed for on-chain settlement.
     *
     * @throws EMVException on any protocol error or missing data.
     */
    fun runEMVFlow(isoDep: IsoDep, amountCents: Int): EMVResult {
        isoDep.timeout = 5000 // 5 second timeout
        isoDep.connect()

        try {
            // ---- Step 1: SELECT ----
            Log.d(TAG, "SELECT AID: ${AID.toHex()}")
            val selectResp = isoDep.transceive(buildSelectApdu(AID))
            checkSW(selectResp, "SELECT")
            val selectData = stripSW(selectResp)
            Log.d(TAG, "SELECT response: ${selectData.toHex()}")

            // Parse PDOL from SELECT response (tag 9F38)
            val selectTlv = TLVParser.parse(selectData)
            val pdolEntry = TLVParser.findTag(selectTlv, "9F38")

            // ---- Step 2: GET PROCESSING OPTIONS ----
            // PDOL expects: TTQ(4) + Amount(6) + Currency(2) + UN(4) = 16 bytes
            val un = ByteArray(4)
            SecureRandom().nextBytes(un)

            val pdolData = if (pdolEntry != null) {
                buildPDOLData(amountCents, un)
            } else {
                ByteArray(0)
            }

            Log.d(TAG, "GPO with PDOL data (${pdolData.size} bytes): ${pdolData.toHex()}")
            val gpoResp = isoDep.transceive(buildGPOApdu(pdolData))
            checkSW(gpoResp, "GPO")
            val gpoData = stripSW(gpoResp)
            Log.d(TAG, "GPO response: ${gpoData.toHex()}")

            // Parse GPO response for AFL (tag 94) or format 1 (tag 80)
            val gpoTlv = TLVParser.parse(gpoData)
            val aflEntry = TLVParser.findTag(gpoTlv, "94")
                ?: extractAFLFromFormat1(gpoData)

            // ---- Step 3: READ RECORD ----
            val allTlv = mutableListOf<TLVParser.TLVEntry>()
            allTlv.addAll(selectTlv)
            allTlv.addAll(gpoTlv)

            if (aflEntry != null) {
                val afl = aflEntry.value
                // AFL is groups of 4 bytes: [SFI byte] [first record] [last record] [ODA count]
                var i = 0
                while (i + 3 < afl.size) {
                    val sfi = (afl[i].toInt() and 0xFF) shr 3
                    val firstRec = afl[i + 1].toInt() and 0xFF
                    val lastRec = afl[i + 2].toInt() and 0xFF
                    i += 4

                    for (rec in firstRec..lastRec) {
                        Log.d(TAG, "READ RECORD sfi=$sfi rec=$rec")
                        val rrResp = isoDep.transceive(buildReadRecordApdu(rec, sfi))
                        checkSW(rrResp, "READ RECORD sfi=$sfi rec=$rec")
                        val rrData = stripSW(rrResp)
                        allTlv.addAll(TLVParser.parse(rrData))
                    }
                }
            }

            // Extract PAN (tag 5A)
            val panEntry = TLVParser.findTag(allTlv, "5A")
                ?: throw EMVException("PAN (tag 5A) not found in card data")
            val pan = panEntry.value
            Log.d(TAG, "PAN: ${pan.toHex()}")

            // Extract public key components (tags 9F47 = exponent / X, 9F48 = Y)
            val pubKeyXEntry = TLVParser.findTag(allTlv, "9F47")
                ?: throw EMVException("Public key X (tag 9F47) not found")
            val pubKeyYEntry = TLVParser.findTag(allTlv, "9F48")
                ?: throw EMVException("Public key Y (tag 9F48) not found")

            // ---- Step 4: GENERATE AC ----
            val cdol1Data = buildCDOL1Data(amountCents, un = un)
            Log.d(TAG, "GENERATE AC with CDOL1 (${cdol1Data.size} bytes): ${cdol1Data.toHex()}")
            val acResp = isoDep.transceive(buildGenerateACApdu(cdol1Data))
            checkSW(acResp, "GENERATE AC")
            val acData = stripSW(acResp)
            val acTlv = TLVParser.parse(acData)

            // Extract signature from tag 9F4B (64 bytes: r || s)
            val sigEntry = TLVParser.findTag(acTlv, "9F4B")
                ?: throw EMVException("Signature (tag 9F4B) not found in GENERATE AC response")
            val sigBytes = sigEntry.value
            if (sigBytes.size != 64) {
                throw EMVException("Signature must be 64 bytes (r||s), got ${sigBytes.size}")
            }
            val sigR = sigBytes.sliceArray(0 until 32)
            val sigS = sigBytes.sliceArray(32 until 64)

            Log.d(TAG, "EMV flow complete. sigR=${sigR.toHex()}, sigS=${sigS.toHex()}")

            return EMVResult(
                cdol1Data = cdol1Data,
                pan = pan,
                sigR = sigR,
                sigS = sigS,
                pubKeyX = pubKeyXEntry.value,
                pubKeyY = pubKeyYEntry.value
            )
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * If GPO responds with Format 1 (tag 80), the first 2 bytes are AIP and
     * the remaining bytes are AFL.
     */
    private fun extractAFLFromFormat1(gpoData: ByteArray): TLVParser.TLVEntry? {
        if (gpoData.isEmpty()) return null
        val firstTag = gpoData[0].toInt() and 0xFF
        if (firstTag == 0x80 && gpoData.size > 4) {
            val len = gpoData[1].toInt() and 0xFF
            if (2 + len <= gpoData.size && len > 2) {
                val afl = gpoData.sliceArray(4 until 2 + len)
                return TLVParser.TLVEntry("94", afl)
            }
        }
        return null
    }

    /** Check that the last 2 bytes of a response are SW1=90, SW2=00. */
    private fun checkSW(response: ByteArray, command: String) {
        if (response.size < 2) {
            throw EMVException("$command: response too short (${response.size} bytes)")
        }
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) {
            throw EMVException("$command failed: SW=%02X%02X".format(sw1, sw2))
        }
    }

    /** Strip the 2-byte status word from the end of a response. */
    private fun stripSW(response: ByteArray): ByteArray {
        return if (response.size > 2) response.sliceArray(0 until response.size - 2)
        else ByteArray(0)
    }
}

/** Exception type for EMV protocol errors. */
class EMVException(message: String, cause: Throwable? = null) : Exception(message, cause)
