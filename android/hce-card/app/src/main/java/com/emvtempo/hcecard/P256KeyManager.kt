package com.emvtempo.hcecard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * Manages the P-256 (secp256r1) key pair using Android Keystore.
 *
 * The private key never leaves the secure hardware. Public key coordinates
 * are extracted for embedding in EMV READ RECORD responses and for on-chain
 * registration in the CardRegistry contract.
 */
object P256KeyManager {

    private const val TAG = "P256KeyManager"
    private const val KEY_ALIAS = "emv_tempo_card_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Generate a new P-256 key pair in Android Keystore if one does not exist.
     * Safe to call multiple times; will not overwrite an existing key.
     */
    fun generateKeyPair() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            Log.d(TAG, "Key pair already exists in Keystore")
            return
        }

        Log.d(TAG, "Generating new P-256 key pair")

        val paramSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false) // PoC: no user auth needed
            .build()

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        keyPairGenerator.initialize(paramSpec)
        keyPairGenerator.generateKeyPair()

        Log.d(TAG, "Key pair generated successfully")
    }

    /**
     * Check whether a key pair exists in the Keystore.
     */
    fun hasKeyPair(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.containsAlias(KEY_ALIAS)
    }

    /**
     * Get the X coordinate of the P-256 public key as a 32-byte array.
     */
    fun getPublicKeyX(): ByteArray {
        val publicKey = getPublicKey()
        return bigIntTo32Bytes(publicKey.w.affineX)
    }

    /**
     * Get the Y coordinate of the P-256 public key as a 32-byte array.
     */
    fun getPublicKeyY(): ByteArray {
        val publicKey = getPublicKey()
        return bigIntTo32Bytes(publicKey.w.affineY)
    }

    /**
     * Sign data with the private key.
     * Returns (r, s) each as a 32-byte big-endian array.
     *
     * The caller is responsible for hashing the data first if needed.
     * Android Keystore SHA256withECDSA will hash internally, so pass raw data
     * if you want SHA-256 applied, or use NONEwithECDSA for pre-hashed data.
     *
     * For EMV-Tempo: we sign SHA-256(cdol1Data). Since we need the card to sign
     * SHA-256 of the raw CDOL1 data, we use SHA256withECDSA on the raw data.
     */
    fun sign(data: ByteArray): Pair<ByteArray, ByteArray> {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        val privateKeyEntry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKeyEntry.privateKey)
        signature.update(data)
        val derSig = signature.sign()

        return decodeDERSignature(derSig)
    }

    /**
     * Decode a DER-encoded ECDSA signature into (r, s) each as 32 bytes.
     *
     * DER format: 30 <len> 02 <rLen> <r> 02 <sLen> <s>
     */
    private fun decodeDERSignature(der: ByteArray): Pair<ByteArray, ByteArray> {
        var offset = 0

        // SEQUENCE tag
        require(der[offset] == 0x30.toByte()) { "Expected SEQUENCE tag" }
        offset++

        // SEQUENCE length (skip)
        if (der[offset].toInt() and 0xFF > 0x80) {
            offset += 1 + (der[offset].toInt() and 0x7F)
        } else {
            offset++
        }

        // First INTEGER (r)
        require(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for r" }
        offset++
        val rLen = der[offset].toInt() and 0xFF
        offset++
        val rRaw = der.sliceArray(offset until offset + rLen)
        offset += rLen

        // Second INTEGER (s)
        require(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for s" }
        offset++
        val sLen = der[offset].toInt() and 0xFF
        offset++
        val sRaw = der.sliceArray(offset until offset + sLen)

        // Normalize to exactly 32 bytes (strip leading zeros or left-pad)
        val r = normalize32(rRaw)
        val s = normalize32(sRaw)

        return Pair(r, s)
    }

    /**
     * Normalize a big-endian integer byte array to exactly 32 bytes.
     * Strips leading zero padding or left-pads with zeros.
     */
    private fun normalize32(raw: ByteArray): ByteArray {
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> {
                // DER may prepend a 0x00 byte for sign; strip leading zeros
                val start = raw.size - 32
                raw.sliceArray(start until raw.size)
            }
            else -> {
                // Left-pad with zeros
                val padded = ByteArray(32)
                raw.copyInto(padded, destinationOffset = 32 - raw.size)
                padded
            }
        }
    }

    private fun getPublicKey(): ECPublicKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey as ECPublicKey
    }

    /**
     * Convert a BigInteger to a 32-byte big-endian array.
     */
    private fun bigIntTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray() // signed big-endian
        return normalize32(raw)
    }
}
