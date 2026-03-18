package com.emvtempo.terminal

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * Main terminal activity for the EMV-Tempo PoC.
 *
 * Presents a numeric keypad for amount entry, initiates the NFC reader mode
 * to accept contactless card taps, runs the EMV flow, and submits the
 * resulting data to the Tempo chain for settlement.
 */
class TerminalActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "TerminalActivity"

        /** NFC reader mode flags: NFC-A without NDEF check for raw IsoDep. */
        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    // UI elements
    private lateinit var etAmount: EditText
    private lateinit var btnCharge: MaterialButton
    private lateinit var nfcStatusArea: View
    private lateinit var tvTapCard: TextView
    private lateinit var tvNfcStatus: TextView
    private lateinit var resultArea: View
    private lateinit var tvResultStatus: TextView
    private lateinit var tvResultAmount: TextView
    private lateinit var tvResultMerchant: TextView
    private lateinit var tvTxHash: TextView
    private lateinit var tvExplorerLink: TextView
    private lateinit var tvResultError: TextView

    private var nfcAdapter: NfcAdapter? = null
    private var currentAmountCents: Int = 0
    private var isWaitingForCard: Boolean = false
    private var lastPanHex: String = ""

    private lateinit var tempoSubmitter: TempoSubmitter

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        tempoSubmitter = TempoSubmitter(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        bindViews()
        setupKeypad()
        setupChargeButton()
        setupExplorerLink()

        // Initialize the built-in thermal printer
        ReceiptPrinter.init(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        ReceiptPrinter.release(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (isWaitingForCard) {
            enableReaderMode()
        }
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        etAmount = findViewById(R.id.etAmount)
        btnCharge = findViewById(R.id.btnCharge)
        nfcStatusArea = findViewById(R.id.nfcStatusArea)
        tvTapCard = findViewById(R.id.tvTapCard)
        tvNfcStatus = findViewById(R.id.tvNfcStatus)
        resultArea = findViewById(R.id.resultArea)
        tvResultStatus = findViewById(R.id.tvResultStatus)
        tvResultAmount = findViewById(R.id.tvResultAmount)
        tvResultMerchant = findViewById(R.id.tvResultMerchant)
        tvTxHash = findViewById(R.id.tvTxHash)
        tvExplorerLink = findViewById(R.id.tvExplorerLink)
        tvResultError = findViewById(R.id.tvResultError)

        // Disable soft keyboard on the amount field (we use our own keypad)
        etAmount.showSoftInputOnFocus = false
    }

    // -------------------------------------------------------------------------
    // Numeric keypad
    // -------------------------------------------------------------------------

    private fun setupKeypad() {
        val digitButtons = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9", R.id.btnDot to "."
        )

        for ((id, digit) in digitButtons) {
            findViewById<MaterialButton>(id).setOnClickListener {
                appendDigit(digit)
            }
        }

        findViewById<MaterialButton>(R.id.btnBackspace).setOnClickListener {
            val text = etAmount.text.toString()
            if (text.isNotEmpty()) {
                etAmount.setText(text.dropLast(1))
                etAmount.setSelection(etAmount.text.length)
            }
        }
    }

    private fun appendDigit(digit: String) {
        val current = etAmount.text.toString()

        // Prevent multiple decimal points
        if (digit == "." && current.contains(".")) return

        // Limit to 2 decimal places
        val dotIndex = current.indexOf('.')
        if (dotIndex >= 0 && digit != "." && current.length - dotIndex > 2) return

        // Limit total length
        if (current.length >= 10) return

        etAmount.setText(current + digit)
        etAmount.setSelection(etAmount.text.length)
    }

    // -------------------------------------------------------------------------
    // Charge flow
    // -------------------------------------------------------------------------

    private fun setupChargeButton() {
        btnCharge.setOnClickListener {
            if (isWaitingForCard) {
                // Cancel the current charge
                cancelCharge()
                return@setOnClickListener
            }

            val amountStr = etAmount.text.toString().trim()
            if (amountStr.isEmpty() || amountStr == ".") {
                Toast.makeText(this, "Enter an amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amountDollars = amountStr.toDoubleOrNull()
            if (amountDollars == null || amountDollars <= 0) {
                Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentAmountCents = (amountDollars * 100).toInt()
            startCharge()
        }
    }

    private fun startCharge() {
        isWaitingForCard = true
        resultArea.visibility = View.GONE
        nfcStatusArea.visibility = View.VISIBLE
        tvTapCard.text = "Tap Card"
        tvNfcStatus.text = "Waiting for card..."
        btnCharge.text = "CANCEL"
        btnCharge.setBackgroundColor(0xFFF44336.toInt()) // Red
        etAmount.isEnabled = false

        enableReaderMode()
    }

    private fun cancelCharge() {
        isWaitingForCard = false
        disableReaderMode()
        nfcStatusArea.visibility = View.GONE
        btnCharge.text = "CHARGE"
        btnCharge.setBackgroundColor(0xFF4CAF50.toInt()) // Green
        etAmount.isEnabled = true
    }

    private fun setupExplorerLink() {
        tvExplorerLink.setOnClickListener {
            val txHash = tvTxHash.tag as? String ?: return@setOnClickListener
            val url = tempoSubmitter.getExplorerUrl(txHash)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    // -------------------------------------------------------------------------
    // NFC reader mode
    // -------------------------------------------------------------------------

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        val extras = Bundle()
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        adapter.enableReaderMode(this, this, READER_FLAGS, extras)
        Log.d(TAG, "NFC reader mode enabled")
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        Log.d(TAG, "NFC reader mode disabled")
    }

    /**
     * Called on a background thread when a tag is discovered.
     */
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null || !isWaitingForCard) return

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread {
                tvNfcStatus.text = "Card does not support IsoDep"
            }
            return
        }

        runOnUiThread {
            tvTapCard.text = "Reading Card..."
            tvNfcStatus.text = "Processing EMV transaction"
        }

        // Run the EMV flow on this background thread
        try {
            Log.d(TAG, "Starting EMV flow for amount: $currentAmountCents cents")
            val emvResult = EMVDriver.runEMVFlow(isoDep, currentAmountCents)
            lastPanHex = emvResult.pan.toHex()

            runOnUiThread {
                tvNfcStatus.text = "Card read complete. Submitting to chain..."
            }

            // Submit to Tempo chain
            Log.d(TAG, "Submitting settlement to Tempo chain")
            val settlementResult = tempoSubmitter.submitSettlement(emvResult)

            // Print receipt on success (still on background thread — printer is blocking)
            if (settlementResult.success) {
                ReceiptPrinter.printReceipt(
                    amountCents = currentAmountCents,
                    txHash = settlementResult.txHash,
                    blockNumber = settlementResult.blockNumber,
                    pan = lastPanHex,
                    terminalId = "TERM001",
                    explorerUrl = tempoSubmitter.getExplorerUrl(settlementResult.txHash)
                )
            }

            runOnUiThread {
                disableReaderMode()
                isWaitingForCard = false
                showResult(settlementResult)
            }

        } catch (e: EMVException) {
            Log.e(TAG, "EMV flow error", e)
            runOnUiThread {
                disableReaderMode()
                isWaitingForCard = false
                showError("EMV Error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during EMV flow", e)
            runOnUiThread {
                disableReaderMode()
                isWaitingForCard = false
                showError("Error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Result display
    // -------------------------------------------------------------------------

    private fun showResult(result: TempoSubmitter.SettlementResult) {
        nfcStatusArea.visibility = View.GONE
        resultArea.visibility = View.VISIBLE
        btnCharge.text = "CHARGE"
        btnCharge.setBackgroundColor(0xFF4CAF50.toInt())
        etAmount.isEnabled = true

        val amountStr = String.format(Locale.US, "$%.2f", currentAmountCents / 100.0)

        if (result.success) {
            tvResultStatus.text = "Payment Settled"
            tvResultStatus.setTextColor(0xFF4CAF50.toInt()) // Green
            tvResultAmount.text = amountStr
            tvResultAmount.visibility = View.VISIBLE
            tvResultMerchant.text = "Terminal: TERM001"
            tvResultMerchant.visibility = View.VISIBLE
            tvTxHash.text = "Tx: ${result.txHash}"
            tvTxHash.tag = result.txHash
            tvTxHash.visibility = View.VISIBLE
            tvExplorerLink.visibility = View.VISIBLE
            tvResultError.visibility = View.GONE
        } else {
            tvResultStatus.text = "Settlement Failed"
            tvResultStatus.setTextColor(0xFFF44336.toInt()) // Red
            tvResultAmount.text = amountStr
            tvResultAmount.visibility = View.VISIBLE
            tvResultMerchant.visibility = View.GONE
            tvTxHash.visibility = View.GONE
            tvExplorerLink.visibility = View.GONE
            tvResultError.text = result.errorMessage ?: "Unknown error"
            tvResultError.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        nfcStatusArea.visibility = View.GONE
        resultArea.visibility = View.VISIBLE
        btnCharge.text = "CHARGE"
        btnCharge.setBackgroundColor(0xFF4CAF50.toInt())
        etAmount.isEnabled = true

        tvResultStatus.text = "Error"
        tvResultStatus.setTextColor(0xFFF44336.toInt())
        tvResultAmount.visibility = View.GONE
        tvResultMerchant.visibility = View.GONE
        tvTxHash.visibility = View.GONE
        tvExplorerLink.visibility = View.GONE
        tvResultError.text = message
        tvResultError.visibility = View.VISIBLE
    }
}
