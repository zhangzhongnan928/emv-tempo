package com.emvtempo.hcecard

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.emvtempo.hcecard.EMVResponder.bytesToHex
import com.emvtempo.hcecard.EMVResponder.concat
import com.emvtempo.hcecard.EMVResponder.encodeTLV
import com.emvtempo.hcecard.EMVResponder.encodeTLVList
import com.emvtempo.hcecard.EMVResponder.hexToBytes
import com.emvtempo.hcecard.EMVResponder.wrapTLV

/**
 * Host Card Emulation service that simulates an EMV contactless card.
 *
 * Handles the four APDU commands in a standard EMV contactless transaction:
 *   1. SELECT (AID A000006690820001)
 *   2. GET PROCESSING OPTIONS
 *   3. READ RECORD
 *   4. GENERATE AC
 *
 * This service MUST NOT use the internet. All NFC-time responses are computed
 * locally using the Android Keystore P-256 key.
 */
class HCEService : HostApduService() {

    companion object {
        private const val TAG = "HCEService"

        /** EMV-Tempo PoC AID */
        private const val AID_HEX = "A000006690820001"

        /** Hardcoded PAN for PoC */
        private const val PAN_HEX = "6690820000000001"

        /** Expiry date: YYMMDD BCD */
        private const val EXPIRY_HEX = "261231"

        /** Issuer country code (Australia = 0036) */
        private const val ISSUER_COUNTRY_HEX = "0036"

        /** CDOL1 DOL definition:
         *  9F02(6) + 9F03(6) + 9F1A(2) + 95(5) + 5F2A(2) + 9A(3) + 9C(1) + 9F37(4) = 29 bytes
         */
        private const val CDOL1_DOL_HEX = "9F02069F03069F1A0295055F2A029A039C019F3704"

        /** Expected CDOL1 data length from terminal */
        private const val CDOL1_DATA_LENGTH = 29

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
    }

    /** Application Transaction Counter, incremented on each GENERATE AC */
    private var atc: Int = 0

    /** Pre-cached public key coordinates to avoid slow Keystore access during NFC */
    private lateinit var cachedPubKeyX: ByteArray
    private lateinit var cachedPubKeyY: ByteArray

    /** Pre-built static responses to avoid latency during NFC */
    private lateinit var cachedReadRecordResponse: ByteArray
    private lateinit var cachedSelectResponse: ByteArray
    private lateinit var cachedGPOResponse: ByteArray

    override fun onCreate() {
        super.onCreate()
        // Ensure key pair exists before any NFC interaction
        P256KeyManager.generateKeyPair()

        // Pre-cache public key and build all static responses at startup
        // so we never touch the Keystore during an NFC exchange
        cachedPubKeyX = P256KeyManager.getPublicKeyX()
        cachedPubKeyY = P256KeyManager.getPublicKeyY()
        cachedSelectResponse = buildSelectResponse()
        cachedGPOResponse = buildGPOResponse()
        cachedReadRecordResponse = buildReadRecordResponse()

        Log.d(TAG, "HCEService created, all responses pre-cached")
        Log.d(TAG, "  SELECT: ${cachedSelectResponse.size}B, GPO: ${cachedGPOResponse.size}B, READ RECORD: ${cachedReadRecordResponse.size}B")
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.size < 4) {
            Log.w(TAG, "APDU too short: ${commandApdu.size} bytes")
            return SW_INS_NOT_SUPPORTED
        }

        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        Log.d(TAG, "APDU: CLA=%02X INS=%02X P1=%02X P2=%02X len=${commandApdu.size}".format(cla, ins, p1, p2))
        Log.d(TAG, "APDU hex: ${bytesToHex(commandApdu)}")

        return when {
            // SELECT by AID
            cla == 0x00 && ins == 0xA4 && p1 == 0x04 && p2 == 0x00 -> handleSelect(commandApdu)

            // GET PROCESSING OPTIONS
            cla == 0x80 && ins == 0xA8 && p1 == 0x00 && p2 == 0x00 -> handleGPO(commandApdu)

            // READ RECORD
            cla == 0x00 && ins == 0xB2 -> handleReadRecord(commandApdu)

            // GENERATE AC
            cla == 0x80 && ins == 0xAE -> handleGenerateAC(commandApdu)

            else -> {
                Log.w(TAG, "Unknown INS: %02X".format(ins))
                SW_INS_NOT_SUPPORTED
            }
        }
    }

    /** Build SELECT response once at startup. */
    private fun buildSelectResponse(): ByteArray {
        val expectedAid = hexToBytes(AID_HEX)
        val pdol = hexToBytes("9F660400" + "9F020600" + "5F2A0200" + "9F370400")
        val fciPropData = encodeTLVList(listOf(
            "50" to "AUDS PAY".toByteArray(Charsets.US_ASCII),
            "9F38" to pdol
        ))
        val fciInner = encodeTLVList(listOf(
            "84" to expectedAid,
            "A5" to fciPropData
        ))
        return concat(wrapTLV("6F", fciInner), SW_OK)
    }

    /** Build GPO response once at startup. */
    private fun buildGPOResponse(): ByteArray {
        val aip = hexToBytes("3900")
        val afl = hexToBytes("08010100")
        val responseData = encodeTLVList(listOf(
            "82" to aip,
            "94" to afl
        ))
        return concat(wrapTLV("77", responseData), SW_OK)
    }

    /** Handle SELECT — return cached response instantly. */
    private fun handleSelect(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val aidData = apdu.sliceArray(5 until 5 + lc)
        if (!aidData.contentEquals(hexToBytes(AID_HEX))) {
            Log.w(TAG, "AID mismatch: ${bytesToHex(aidData)}")
            return SW_FILE_NOT_FOUND
        }
        Log.d(TAG, "SELECT OK")
        return cachedSelectResponse
    }

    /** Handle GPO — return cached response instantly. */
    private fun handleGPO(apdu: ByteArray): ByteArray {
        Log.d(TAG, "GPO OK")
        return cachedGPOResponse
    }

    /**
     * Build the READ RECORD response at startup (called once, cached).
     */
    private fun buildReadRecordResponse(): ByteArray {
        val pan = hexToBytes(PAN_HEX)
        val expiry = hexToBytes(EXPIRY_HEX)
        val issuerCountry = hexToBytes(ISSUER_COUNTRY_HEX)

        // Track 2: PAN + D + Expiry(YYMM) + ServiceCode + Discretionary + FF pad
        val track2 = hexToBytes(
            PAN_HEX + "D" + EXPIRY_HEX.substring(0, 4) + "201" + "0000000000" + "FF"
        )

        val cdol1Dol = hexToBytes(CDOL1_DOL_HEX)

        val recordData = encodeTLVList(listOf(
            "57" to track2,
            "5A" to pan,
            "5F24" to expiry,
            "5F28" to issuerCountry,
            "8C" to cdol1Dol,
            "9F47" to cachedPubKeyX,
            "9F48" to cachedPubKeyY
        ))

        val record = wrapTLV("70", recordData)
        return concat(record, SW_OK)
    }

    /**
     * Handle READ RECORD.
     * Returns the pre-cached response immediately (no Keystore access).
     */
    private fun handleReadRecord(apdu: ByteArray): ByteArray {
        Log.d(TAG, "Processing READ RECORD (cached, ${cachedReadRecordResponse.size} bytes)")
        Log.d(TAG, "READ RECORD response: ${bytesToHex(cachedReadRecordResponse)}")
        return cachedReadRecordResponse
    }

    /**
     * Handle GENERATE AC.
     *
     * Receives 29-byte CDOL1 data from the terminal, signs SHA-256(cdol1Data)
     * with the P-256 private key, and returns:
     *   - CID (tag 9F27): 0x80 (ARQC)
     *   - ATC (tag 9F36): 2-byte counter
     *   - Application Cryptogram (tag 9F26): first 8 bytes of SHA-256(cdol1Data)
     *   - Signed Dynamic Application Data (tag 9F4B): r(32) || s(32) = 64 bytes
     */
    private fun handleGenerateAC(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH

        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH

        val cdol1Data = apdu.sliceArray(5 until 5 + lc)

        if (cdol1Data.size != CDOL1_DATA_LENGTH) {
            Log.w(TAG, "CDOL1 data length mismatch: expected $CDOL1_DATA_LENGTH, got ${cdol1Data.size}")
            return SW_WRONG_LENGTH
        }

        Log.d(TAG, "GENERATE AC with CDOL1 data: ${bytesToHex(cdol1Data)}")

        // Sign SHA-256(cdol1Data) using Android Keystore
        // SHA256withECDSA computes SHA-256 internally, so pass raw data
        val (r, s) = P256KeyManager.sign(cdol1Data)

        // Increment ATC
        atc++

        // Compute SHA-256(cdol1Data) for the cryptogram (first 8 bytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cdol1Data)
        val cryptogram = hash.sliceArray(0 until 8)

        // ATC as 2 bytes big-endian
        val atcBytes = byteArrayOf(
            ((atc shr 8) and 0xFF).toByte(),
            (atc and 0xFF).toByte()
        )

        // Signed Dynamic Application Data: r(32) || s(32)
        val signedData = r + s

        val responseData = encodeTLVList(listOf(
            "9F27" to byteArrayOf(0x80.toByte()),   // CID: ARQC
            "9F36" to atcBytes,                      // ATC
            "9F26" to cryptogram,                    // Application Cryptogram
            "9F4B" to signedData                     // Signed Dynamic Application Data
        ))

        val response = concat(wrapTLV("77", responseData), SW_OK)

        Log.d(TAG, "GENERATE AC response: ${bytesToHex(response)}")
        Log.d(TAG, "  r: ${bytesToHex(r)}")
        Log.d(TAG, "  s: ${bytesToHex(s)}")
        Log.d(TAG, "  ATC: $atc")
        return response
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
        Log.d(TAG, "Deactivated: $reasonStr")
    }
}
