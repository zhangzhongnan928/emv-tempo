package com.emvtempo.hcecard

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emvtempo.hcecard.EMVResponder.bytesToHex
import kotlinx.coroutines.launch

/**
 * Settings activity for the EMV-Tempo HCE card app.
 *
 * Displays card technical details (PAN, public key) and provides
 * on-chain registration controls. Moved here to keep the main
 * card view clean and wallet-like.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PAN_HEX = "6690820000000001"
    }

    private lateinit var tvPan: TextView
    private lateinit var tvPubKeyX: TextView
    private lateinit var tvPubKeyY: TextView
    private lateinit var tvKeyStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etRpcUrl: EditText
    private lateinit var etContractAddress: EditText
    private lateinit var etPrivateKey: EditText
    private lateinit var etCardholderAddress: EditText
    private lateinit var btnRegister: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Card Settings"

        tvPan = findViewById(R.id.tvPan)
        tvPubKeyX = findViewById(R.id.tvPubKeyX)
        tvPubKeyY = findViewById(R.id.tvPubKeyY)
        tvKeyStatus = findViewById(R.id.tvKeyStatus)
        tvStatus = findViewById(R.id.tvStatus)
        etRpcUrl = findViewById(R.id.etRpcUrl)
        etContractAddress = findViewById(R.id.etContractAddress)
        etPrivateKey = findViewById(R.id.etPrivateKey)
        etCardholderAddress = findViewById(R.id.etCardholderAddress)
        btnRegister = findViewById(R.id.btnRegister)

        displayCardInfo()
        btnRegister.setOnClickListener { onRegisterClicked() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun displayCardInfo() {
        val panFormatted = PAN_HEX.chunked(4).joinToString(" ")
        tvPan.text = "PAN: $panFormatted"

        if (P256KeyManager.hasKeyPair()) {
            val pubKeyX = bytesToHex(P256KeyManager.getPublicKeyX())
            val pubKeyY = bytesToHex(P256KeyManager.getPublicKeyY())
            tvPubKeyX.text = "PubKey X: $pubKeyX"
            tvPubKeyY.text = "PubKey Y: $pubKeyY"
            tvKeyStatus.text = "Key: P-256 (Android Keystore)"
        } else {
            tvPubKeyX.text = "PubKey X: --"
            tvPubKeyY.text = "PubKey Y: --"
            tvKeyStatus.text = "Key: NOT GENERATED"
        }
    }

    private fun onRegisterClicked() {
        val rpcUrl = etRpcUrl.text.toString().trim()
        val contractAddress = etContractAddress.text.toString().trim()
        val privateKey = etPrivateKey.text.toString().trim()
        val cardholderAddress = etCardholderAddress.text.toString().trim()

        if (rpcUrl.isEmpty()) { setStatus("Error: RPC URL is required"); return }
        if (contractAddress.isEmpty()) { setStatus("Error: Contract address is required"); return }
        if (privateKey.isEmpty()) { setStatus("Error: Wallet private key is required"); return }
        if (cardholderAddress.isEmpty()) { setStatus("Error: Cardholder address is required"); return }
        if (!P256KeyManager.hasKeyPair()) { setStatus("Error: No key pair generated"); return }

        val pubKeyX = P256KeyManager.getPublicKeyX()
        val pubKeyY = P256KeyManager.getPublicKeyY()

        btnRegister.isEnabled = false
        setStatus("Registering card on-chain...")

        lifecycleScope.launch {
            val result = CardRegistration.registerCard(
                rpcUrl = rpcUrl,
                contractAddress = contractAddress,
                privateKeyHex = privateKey,
                panHex = PAN_HEX,
                pubKeyX = pubKeyX,
                pubKeyY = pubKeyY,
                cardholderAddress = cardholderAddress
            )

            when (result) {
                is CardRegistration.Result.Success -> {
                    setStatus("Registered! TX: ${result.txHash}")
                }
                is CardRegistration.Result.Error -> {
                    setStatus("Registration failed: ${result.message}")
                }
            }

            btnRegister.isEnabled = true
        }
    }

    private fun setStatus(message: String) {
        tvStatus.text = message
    }
}
