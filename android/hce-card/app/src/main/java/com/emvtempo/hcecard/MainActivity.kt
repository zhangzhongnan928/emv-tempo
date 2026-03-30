package com.emvtempo.hcecard

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.emvtempo.hcecard.EMVResponder.bytesToHex

/**
 * Main activity — Apple Wallet-style card view.
 *
 * Shows the OPK payment card with full PAN and a "Hold Near Reader"
 * NFC prompt. All settings and registration are in SettingsActivity.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAN_HEX = "6690820000000001"
    }

    private lateinit var tvPan: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPan = findViewById(R.id.tvPan)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        // Generate key pair on first launch
        P256KeyManager.generateKeyPair()

        // Log public key for setup
        if (P256KeyManager.hasKeyPair()) {
            Log.i("EMV_CARD_KEY", "PubKeyX=0x${bytesToHex(P256KeyManager.getPublicKeyX())}")
            Log.i("EMV_CARD_KEY", "PubKeyY=0x${bytesToHex(P256KeyManager.getPublicKeyY())}")
        }

        // Display card PAN
        val panFormatted = PAN_HEX.chunked(4).joinToString(" ")
        tvPan.text = panFormatted

        // Check NFC
        checkNfc()

        // Settings button
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        checkNfc()
    }

    private fun checkNfc() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> {
                tvStatus.text = "NFC not available"
                tvStatus.setTextColor(getColor(R.color.status_error))
            }
            !nfcAdapter.isEnabled -> {
                tvStatus.text = "Enable NFC in Settings"
                tvStatus.setTextColor(getColor(R.color.status_error))
            }
            else -> {
                tvStatus.text = "Ready"
                tvStatus.setTextColor(getColor(R.color.status_ready))
            }
        }
    }
}
