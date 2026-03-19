package com.emvtempo.terminal

import android.content.Context
import android.util.Log
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Bytes8
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Submits EMV settlement transactions to the Tempo testnet.
 *
 * Connects to the Moderato testnet RPC endpoint, builds and signs
 * a call to EMVSettlement.settle(), and returns the transaction hash.
 */
class TempoSubmitter(private val context: Context) {

    companion object {
        private const val TAG = "TempoSubmitter"

        /** Tempo Moderato testnet RPC. */
        private const val RPC_URL = "https://rpc.moderato.tempo.xyz"

        /** Chain ID for Tempo Moderato. */
        private val CHAIN_ID = 42431L

        /** Terminal ID: "TERM001\0" in hex. */
        private const val TERMINAL_ID_HEX = "5445524D30303100"

        /** SharedPreferences key for settlement contract address. */
        private const val PREF_CONTRACT_ADDRESS = "settlement_contract_address"

        /** SharedPreferences key for terminal wallet private key. */
        private const val PREF_TERMINAL_PRIVATE_KEY = "terminal_private_key"

        /** SharedPreferences file name. */
        private const val PREFS_NAME = "emv_tempo_prefs"

        /** Deployed EMVSettlement contract address on Tempo Moderato. */
        private const val DEFAULT_CONTRACT_ADDRESS = "0xABcCc21F466ebd68494255790021c718B9DBF81d"

        /** Default PoC private key for terminal wallet. Set via SharedPreferences. */
        private const val DEFAULT_PRIVATE_KEY = ""

        /** Gas limit for settlement transactions. */
        private val GAS_LIMIT = BigInteger.valueOf(1_500_000)
    }

    private val web3j: Web3j by lazy {
        Web3j.build(HttpService(RPC_URL))
    }

    data class SettlementResult(
        val success: Boolean,
        val txHash: String,
        val blockNumber: Long = 0,
        val errorMessage: String? = null
    )

    /**
     * Submit an EMV settlement transaction to the Tempo chain.
     *
     * Calls EMVSettlement.settle(cdol1Data, pan, terminalId, sigR, sigS)
     * using the terminal's own wallet for gas.
     *
     * @param emvResult The result from the EMV contactless flow.
     * @return A [SettlementResult] with the tx hash or error.
     */
    fun submitSettlement(emvResult: EMVDriver.EMVResult): SettlementResult {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Load terminal wallet credentials
            val privateKey = prefs.getString(PREF_TERMINAL_PRIVATE_KEY, DEFAULT_PRIVATE_KEY)!!
            val credentials = Credentials.create(privateKey)
            Log.d(TAG, "Terminal wallet address: ${credentials.address}")

            // Load contract address
            val contractAddress = prefs.getString(PREF_CONTRACT_ADDRESS, null)
                ?: DEFAULT_CONTRACT_ADDRESS
            Log.d(TAG, "Settlement contract: $contractAddress")

            // Build the settle() function call
            val terminalIdBytes = TERMINAL_ID_HEX.hexToBytes()

            val function = Function(
                "settle",
                listOf(
                    DynamicBytes(emvResult.cdol1Data),          // bytes cdol1Data
                    Bytes8(padTo8(emvResult.pan)),               // bytes8 pan
                    Bytes8(padTo8(terminalIdBytes)),             // bytes8 terminalId
                    Bytes32(padTo32(emvResult.sigR)),            // bytes32 sigR
                    Bytes32(padTo32(emvResult.sigS)),            // bytes32 sigS
                ),
                emptyList()
            )
            val encodedFunction = FunctionEncoder.encode(function)
            Log.d(TAG, "Encoded settle() call: ${encodedFunction.length / 2} bytes")

            // Get nonce
            val nonce = web3j.ethGetTransactionCount(
                credentials.address,
                DefaultBlockParameterName.LATEST
            ).send().transactionCount
            Log.d(TAG, "Nonce: $nonce")

            // Get gas price
            val gasPrice = web3j.ethGasPrice().send().gasPrice
            Log.d(TAG, "Gas price: $gasPrice")

            // Build and sign the transaction
            val rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                GAS_LIMIT,
                contractAddress,
                BigInteger.ZERO, // value (no ETH/pathUSD sent with call)
                encodedFunction
            )

            val signedMessage = TransactionEncoder.signMessage(
                rawTransaction,
                CHAIN_ID,
                credentials
            )
            val hexValue = Numeric.toHexString(signedMessage)

            // Send the transaction
            Log.d(TAG, "Sending settlement transaction...")
            val transactionResponse = web3j.ethSendRawTransaction(hexValue).send()

            if (transactionResponse.hasError()) {
                val error = transactionResponse.error.message
                Log.e(TAG, "Transaction error: $error")
                SettlementResult(
                    success = false,
                    txHash = "",
                    errorMessage = error
                )
            } else {
                val txHash = transactionResponse.transactionHash
                Log.d(TAG, "Transaction sent: $txHash")

                // Wait for receipt (with timeout)
                val receipt = waitForReceipt(txHash, timeoutMs = 30_000)
                if (receipt != null) {
                    val blk = receipt.blockNumber?.toLong() ?: 0L
                    Log.d(TAG, "Transaction confirmed in block $blk")
                    SettlementResult(success = true, txHash = txHash, blockNumber = blk)
                } else {
                    Log.w(TAG, "Transaction sent but receipt not yet available")
                    SettlementResult(success = true, txHash = txHash)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Settlement failed", e)
            SettlementResult(
                success = false,
                txHash = "",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Poll for a transaction receipt with a timeout.
     */
    private fun waitForReceipt(
        txHash: String,
        timeoutMs: Long,
        intervalMs: Long = 2_000
    ): org.web3j.protocol.core.methods.response.TransactionReceipt? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val receipt = web3j.ethGetTransactionReceipt(txHash).send()
                if (receipt.transactionReceipt.isPresent) {
                    return receipt.transactionReceipt.get()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error polling receipt: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
        return null
    }

    /**
     * Get the explorer URL for a transaction hash.
     */
    fun getExplorerUrl(txHash: String): String {
        return "https://explore.testnet.tempo.xyz/tx/$txHash"
    }

    /** Pad a byte array to exactly 32 bytes (right-padded with zeros). */
    private fun padTo32(data: ByteArray): ByteArray {
        if (data.size >= 32) return data.sliceArray(0 until 32)
        val padded = ByteArray(32)
        System.arraycopy(data, 0, padded, 0, data.size)
        return padded
    }

    /** Pad a byte array to exactly 8 bytes (right-padded with zeros). */
    private fun padTo8(data: ByteArray): ByteArray {
        if (data.size >= 8) return data.sliceArray(0 until 8)
        val padded = ByteArray(8)
        System.arraycopy(data, 0, padded, 0, data.size)
        return padded
    }
}
