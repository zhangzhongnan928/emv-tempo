package com.emvtempo.hcecard

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes8
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * One-time on-chain card registration.
 *
 * Registers the card's PAN and P-256 public key on the Tempo testnet
 * by calling CardRegistry.registerCard(pan, pubKeyX, pubKeyY, cardholder).
 *
 * This is the ONLY component in the app that uses the internet.
 */
object CardRegistration {

    private const val TAG = "CardRegistration"

    /**
     * Registration result returned to the UI.
     */
    sealed class Result {
        data class Success(val txHash: String) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Register the card on-chain.
     *
     * @param rpcUrl           Tempo testnet JSON-RPC URL
     * @param contractAddress  CardRegistry contract address
     * @param privateKeyHex    Wallet private key (hex, no 0x prefix) used to sign the tx
     * @param panHex           8-byte PAN as hex (e.g., "6690820000000001")
     * @param pubKeyX          32-byte X coordinate of the card's P-256 public key
     * @param pubKeyY          32-byte Y coordinate of the card's P-256 public key
     * @param cardholderAddress Tempo address that holds AUDS tokens
     */
    suspend fun registerCard(
        rpcUrl: String,
        contractAddress: String,
        privateKeyHex: String,
        panHex: String,
        pubKeyX: ByteArray,
        pubKeyY: ByteArray,
        cardholderAddress: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting card registration...")
            Log.d(TAG, "  RPC: $rpcUrl")
            Log.d(TAG, "  Contract: $contractAddress")
            Log.d(TAG, "  PAN: $panHex")
            Log.d(TAG, "  Cardholder: $cardholderAddress")

            val web3j = Web3j.build(HttpService(rpcUrl))
            val credentials = Credentials.create(privateKeyHex)

            // Encode function call: registerCard(bytes8, bytes32, bytes32, address)
            val panBytes = EMVResponder.hexToBytes(panHex)
            require(panBytes.size == 8) { "PAN must be 8 bytes, got ${panBytes.size}" }
            require(pubKeyX.size == 32) { "pubKeyX must be 32 bytes" }
            require(pubKeyY.size == 32) { "pubKeyY must be 32 bytes" }

            val function = org.web3j.abi.datatypes.Function(
                "registerCard",
                listOf(
                    Bytes8(panBytes),
                    Bytes32(pubKeyX),
                    Bytes32(pubKeyY),
                    Address(cardholderAddress)
                ),
                emptyList()
            )

            val encodedFunction = FunctionEncoder.encode(function)

            // Get nonce
            val nonce = web3j.ethGetTransactionCount(
                credentials.address,
                org.web3j.protocol.core.DefaultBlockParameterName.LATEST
            ).send().transactionCount

            // Get gas price
            val gasPrice = web3j.ethGasPrice().send().gasPrice

            // Build and sign transaction
            val rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                BigInteger.valueOf(200_000), // gas limit
                contractAddress,
                encodedFunction
            )

            val signedMessage = TransactionEncoder.signMessage(rawTransaction, 42431L, credentials)
            val hexValue = Numeric.toHexString(signedMessage)

            // Send transaction
            val txResponse = web3j.ethSendRawTransaction(hexValue).send()

            if (txResponse.hasError()) {
                val errorMsg = txResponse.error.message
                Log.e(TAG, "Registration failed: $errorMsg")
                Result.Error(errorMsg)
            } else {
                val txHash = txResponse.transactionHash
                Log.d(TAG, "Registration tx sent: $txHash")
                Result.Success(txHash)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
