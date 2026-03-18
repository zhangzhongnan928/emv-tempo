package com.emvtempo.terminal

import android.content.Context
import android.util.Log
import com.imin.printer.PrinterHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prints a professional receipt on the iMin Swift 2 built-in thermal printer
 * after a successful EMV settlement on Tempo.
 */
object ReceiptPrinter {

    private const val TAG = "ReceiptPrinter"
    private var initialized = false

    /** Initialize the iMin printer service. Call once from Activity.onCreate(). */
    fun init(context: Context) {
        try {
            PrinterHelper.getInstance().initPrinterService(context)
            initialized = true
            Log.d(TAG, "Printer service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init printer", e)
        }
    }

    /** Release the printer service. Call from Activity.onDestroy(). */
    fun release(context: Context) {
        try {
            PrinterHelper.getInstance().deInitPrinterService(context)
            initialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release printer", e)
        }
    }

    /**
     * Print a settlement receipt.
     *
     * @param amountCents   Amount in cents (e.g. 1000 = $10.00)
     * @param txHash        Transaction hash on Tempo
     * @param blockNumber   Block number the tx was confirmed in
     * @param pan           Card PAN hex (e.g. "6690820000000001")
     * @param terminalId    Terminal ID (e.g. "TERM001")
     * @param explorerUrl   Full explorer URL for the transaction
     */
    fun printReceipt(
        amountCents: Int,
        txHash: String,
        blockNumber: Long,
        pan: String,
        terminalId: String,
        explorerUrl: String
    ) {
        if (!initialized) {
            Log.w(TAG, "Printer not initialized, skipping receipt")
            return
        }

        try {
            val printer = PrinterHelper.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val amountStr = String.format(Locale.US, "$%.2f", amountCents / 100.0)
            val maskedPan = maskPan(pan)
            val shortHash = if (txHash.length > 14) "${txHash.take(10)}...${txHash.takeLast(4)}" else txHash

            // --- Reset printer state ---
            printer.initPrinterParams()

            // === HEADER ===
            printer.setTextBitmapTypeface("Typeface.DEFAULT_BOLD")
            printer.setTextBitmapSize(36)
            printer.setCodeAlignment(1) // Center
            printer.printTextBitmapWithAli("AUDS PAY\n", 1, null)

            printer.setTextBitmapTypeface("Typeface.DEFAULT")
            printer.setTextBitmapSize(22)
            printer.printTextBitmapWithAli("Blockchain Settlement Receipt\n", 1, null)

            // --- Divider ---
            printer.setTextBitmapSize(20)
            printer.printTextBitmapWithAli("--------------------------------\n", 1, null)

            // === APPROVED ===
            printer.setTextBitmapTypeface("Typeface.DEFAULT_BOLD")
            printer.setTextBitmapSize(32)
            printer.printTextBitmapWithAli("APPROVED\n", 1, null)

            // --- Divider ---
            printer.setTextBitmapTypeface("Typeface.DEFAULT")
            printer.setTextBitmapSize(20)
            printer.printTextBitmapWithAli("--------------------------------\n", 1, null)

            // === AMOUNT (large, centered) ===
            printer.setTextBitmapTypeface("Typeface.DEFAULT_BOLD")
            printer.setTextBitmapSize(48)
            printer.printTextBitmapWithAli("$amountStr AUD\n", 1, null)

            printer.setTextBitmapTypeface("Typeface.DEFAULT")
            printer.setTextBitmapSize(20)
            printer.printTextBitmapWithAli("--------------------------------\n", 1, null)

            // === TRANSACTION DETAILS (left-aligned table) ===
            printer.setTextBitmapSize(24)
            printer.setCodeAlignment(0) // Left

            // Use printColumnsString for aligned key-value pairs
            val labels = arrayOf("Card:", "Terminal:", "Date:", "Network:", "Token:", "Tx Hash:", "Block:")
            val values = arrayOf(maskedPan, terminalId, timestamp, "Tempo (42431)", "AUDS", shortHash, blockNumber.toString())

            for (i in labels.indices) {
                printer.printColumnsString(
                    arrayOf(labels[i], values[i]),
                    intArrayOf(1, 2),       // width ratio
                    intArrayOf(0, 2),       // align: label left, value right
                    intArrayOf(22, 22),     // font size
                    null
                )
            }

            // --- Divider ---
            printer.setTextBitmapSize(20)
            printer.setCodeAlignment(1)
            printer.printTextBitmapWithAli("--------------------------------\n", 1, null)

            // === QR CODE (explorer link) ===
            printer.setTextBitmapSize(20)
            printer.printTextBitmapWithAli("Verify on Explorer:\n", 1, null)

            printer.setQrCodeSize(5)
            printer.setQrCodeErrorCorrectionLev(1) // M level
            printer.printQrCodeWithAlign(explorerUrl, 1, null) // Center

            printer.printAndLineFeed()

            // === FOOTER ===
            printer.setTextBitmapSize(18)
            printer.printTextBitmapWithAli("Settled on Tempo Blockchain\n", 1, null)
            printer.printTextBitmapWithAli("P-256 Signature Verified On-Chain\n", 1, null)
            printer.printTextBitmapWithAli("Powered by EMV-Tempo PoC\n", 1, null)

            // Feed paper and cut
            printer.printAndFeedPaper(100)
            printer.partialCut()

            Log.d(TAG, "Receipt printed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to print receipt", e)
        }
    }

    /** Mask PAN for receipt display: show first 4 and last 4 digits */
    private fun maskPan(panHex: String): String {
        if (panHex.length < 8) return panHex
        val first4 = panHex.take(4)
        val last4 = panHex.takeLast(4)
        val masked = "*".repeat((panHex.length - 8).coerceAtLeast(0))
        return "$first4 $masked $last4"
    }
}
