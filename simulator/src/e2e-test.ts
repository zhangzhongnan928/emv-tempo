/**
 * End-to-end test: Card Sim → Terminal Sim → Tempo Testnet Settlement
 *
 * This script:
 * 1. Generates a P-256 keypair (card)
 * 2. Runs the EMV APDU flow (card ↔ terminal)
 * 3. Verifies the P-256 signature locally
 * 4. Optionally submits to Tempo testnet if env vars are set
 *
 * Usage:
 *   npx tsx src/e2e-test.ts                     # Local-only test
 *   PRIVATE_KEY=0x... npx tsx src/e2e-test.ts   # With on-chain submission
 */

import { generateKeyPair, verify, sha256Hash } from "./p256.js";
import { CardSimulator, type CardConfig } from "./card-sim.js";
import { TerminalSimulator, type TerminalConfig } from "./terminal-sim.js";
import { bytesToHex } from "./tlv.js";
import {
  createPublicClient,
  createWalletClient,
  http,
  encodeFunctionData,
  type Hex,
  getAddress,
} from "viem";
import { privateKeyToAccount } from "viem/accounts";

// ============================================================
// Configuration
// ============================================================

const TEMPO_RPC = "https://rpc.moderato.tempo.xyz";
const CHAIN_ID = 42431;

const TEST_PAN = "6690820000000001"; // 16 hex digits (8 bytes)
const TERMINAL_ID = "5445524D30303100"; // "TERM001\0" as hex
const AMOUNT_CENTS = 1000; // $10.00

// ============================================================
// ABI fragments for on-chain interaction
// ============================================================

const CARD_REGISTRY_ABI = [
  {
    name: "registerCard",
    type: "function",
    inputs: [
      { name: "pan", type: "bytes8" },
      { name: "pubKeyX", type: "bytes32" },
      { name: "pubKeyY", type: "bytes32" },
      { name: "cardholder", type: "address" },
    ],
    outputs: [],
    stateMutability: "nonpayable",
  },
  {
    name: "getCard",
    type: "function",
    inputs: [{ name: "pan", type: "bytes8" }],
    outputs: [
      {
        name: "",
        type: "tuple",
        components: [
          { name: "pubKeyX", type: "bytes32" },
          { name: "pubKeyY", type: "bytes32" },
          { name: "cardholder", type: "address" },
          { name: "active", type: "bool" },
        ],
      },
    ],
    stateMutability: "view",
  },
] as const;

const MERCHANT_REGISTRY_ABI = [
  {
    name: "registerMerchant",
    type: "function",
    inputs: [
      { name: "terminalId", type: "bytes8" },
      { name: "merchant", type: "address" },
    ],
    outputs: [],
    stateMutability: "nonpayable",
  },
] as const;

const SETTLEMENT_ABI = [
  {
    name: "settle",
    type: "function",
    inputs: [
      { name: "cdol1Data", type: "bytes" },
      { name: "pan", type: "bytes8" },
      { name: "terminalId", type: "bytes8" },
      { name: "sigR", type: "bytes32" },
      { name: "sigS", type: "bytes32" },
    ],
    outputs: [],
    stateMutability: "nonpayable",
  },
] as const;

const AUDS_ABI = [
  {
    name: "approve",
    type: "function",
    inputs: [
      { name: "spender", type: "address" },
      { name: "amount", type: "uint256" },
    ],
    outputs: [{ name: "", type: "bool" }],
    stateMutability: "nonpayable",
  },
  {
    name: "balanceOf",
    type: "function",
    inputs: [{ name: "account", type: "address" }],
    outputs: [{ name: "", type: "uint256" }],
    stateMutability: "view",
  },
  {
    name: "mint",
    type: "function",
    inputs: [
      { name: "to", type: "address" },
      { name: "amount", type: "uint256" },
    ],
    outputs: [],
    stateMutability: "nonpayable",
  },
] as const;

// ============================================================
// Main
// ============================================================

async function main() {
  console.log("═══════════════════════════════════════════════");
  console.log(" EMV-to-Tempo End-to-End Test");
  console.log("═══════════════════════════════════════════════\n");

  // --- Step 1: Generate card keypair ---
  console.log("[1] Generating P-256 keypair for card...");
  const keyPair = generateKeyPair();
  console.log(`    Public Key X: 0x${bytesToHex(keyPair.publicKeyX)}`);
  console.log(`    Public Key Y: 0x${bytesToHex(keyPair.publicKeyY)}`);

  // --- Step 2: Set up card simulator ---
  console.log("\n[2] Setting up card simulator...");
  const cardConfig: CardConfig = {
    keyPair,
    pan: TEST_PAN,
    expiryDate: "261231",
    issuerCountryCode: "0036",
  };
  const card = new CardSimulator(cardConfig);
  console.log(`    PAN: ${TEST_PAN}`);

  // --- Step 3: Set up terminal simulator ---
  console.log("\n[3] Setting up terminal simulator...");
  const terminalConfig: TerminalConfig = {
    terminalId: TERMINAL_ID,
    amount: AMOUNT_CENTS,
    currencyCode: "0036",
    countryCode: "0036",
  };
  const terminal = new TerminalSimulator(terminalConfig);
  console.log(`    Terminal ID: ${TERMINAL_ID}`);
  console.log(`    Amount: $${(AMOUNT_CENTS / 100).toFixed(2)} AUD`);

  // --- Step 4: Run EMV flow ---
  console.log("\n[4] Running EMV contactless flow...");
  console.log("    ┌─────────────────────────────────────────┐");
  const result = terminal.runTransaction(card);
  console.log("    └─────────────────────────────────────────┘");

  console.log(`\n    CDOL1 Data (29 bytes): 0x${bytesToHex(result.cdol1Data)}`);
  console.log(`    Signature R: 0x${bytesToHex(result.sigR)}`);
  console.log(`    Signature S: 0x${bytesToHex(result.sigS)}`);

  // --- Step 5: Verify signature locally ---
  console.log("\n[5] Verifying P-256 signature locally...");
  const hash = sha256Hash(result.cdol1Data);
  console.log(`    SHA-256(CDOL1): 0x${bytesToHex(hash)}`);

  const valid = verify(hash, { r: result.sigR, s: result.sigS }, result.pubKeyX, result.pubKeyY);
  console.log(`    Signature valid: ${valid}`);

  if (!valid) {
    console.error("\n    ❌ SIGNATURE VERIFICATION FAILED");
    process.exit(1);
  }
  console.log("    ✅ Signature verified successfully!");

  // --- Step 6: On-chain submission (if configured) ---
  const privateKey = process.env.PRIVATE_KEY;
  const settlementAddr = process.env.SETTLEMENT_ADDRESS;
  const cardRegistryAddr = process.env.CARD_REGISTRY_ADDRESS;
  const merchantRegistryAddr = process.env.MERCHANT_REGISTRY_ADDRESS;
  const audsAddr = process.env.AUDS_ADDRESS;

  if (privateKey && settlementAddr && cardRegistryAddr && merchantRegistryAddr && audsAddr) {
    console.log("\n[6] Submitting to Tempo testnet...");
    await submitOnChain(
      privateKey as Hex,
      settlementAddr as Hex,
      cardRegistryAddr as Hex,
      merchantRegistryAddr as Hex,
      audsAddr as Hex,
      result,
      keyPair.publicKeyX,
      keyPair.publicKeyY
    );
  } else {
    console.log("\n[6] Skipping on-chain submission (env vars not set)");
    console.log("    To submit on-chain, set:");
    console.log("      PRIVATE_KEY, SETTLEMENT_ADDRESS, CARD_REGISTRY_ADDRESS,");
    console.log("      MERCHANT_REGISTRY_ADDRESS, AUDS_ADDRESS");
  }

  console.log("\n═══════════════════════════════════════════════");
  console.log(" ✅ E2E Test Complete!");
  console.log("═══════════════════════════════════════════════\n");
}

async function submitOnChain(
  privateKey: Hex,
  settlementAddr: Hex,
  cardRegistryAddr: Hex,
  merchantRegistryAddr: Hex,
  audsAddr: Hex,
  result: ReturnType<TerminalSimulator["runTransaction"]>,
  pubKeyX: Uint8Array,
  pubKeyY: Uint8Array
) {
  const tempoChain = {
    id: CHAIN_ID,
    name: "Tempo Testnet",
    nativeCurrency: { name: "ETH", symbol: "ETH", decimals: 18 },
    rpcUrls: { default: { http: [TEMPO_RPC] } },
  } as const;

  const account = privateKeyToAccount(privateKey);
  console.log(`    Submitter: ${account.address}`);

  const publicClient = createPublicClient({
    chain: tempoChain,
    transport: http(TEMPO_RPC),
  });

  const walletClient = createWalletClient({
    account,
    chain: tempoChain,
    transport: http(TEMPO_RPC),
  });

  const cardholderAddr = account.address; // For PoC, deployer is also cardholder
  const merchantAddr = account.address; // For PoC, deployer is also merchant

  // 6a. Register card
  console.log("    Registering card...");
  try {
    const regCardHash = await walletClient.sendTransaction({
      to: cardRegistryAddr,
      data: encodeFunctionData({
        abi: CARD_REGISTRY_ABI,
        functionName: "registerCard",
        args: [
          `0x${result.pan}` as Hex,
          `0x${bytesToHex(pubKeyX)}` as Hex,
          `0x${bytesToHex(pubKeyY)}` as Hex,
          cardholderAddr,
        ],
      }),
    });
    console.log(`    Card registered: ${regCardHash}`);
    await publicClient.waitForTransactionReceipt({ hash: regCardHash });
  } catch (e: any) {
    if (e.message?.includes("Card already registered")) {
      console.log("    Card already registered, continuing...");
    } else {
      throw e;
    }
  }

  // 6b. Register merchant
  console.log("    Registering merchant...");
  try {
    const regMerchHash = await walletClient.sendTransaction({
      to: merchantRegistryAddr,
      data: encodeFunctionData({
        abi: MERCHANT_REGISTRY_ABI,
        functionName: "registerMerchant",
        args: [`0x${result.terminalId}` as Hex, merchantAddr],
      }),
    });
    console.log(`    Merchant registered: ${regMerchHash}`);
    await publicClient.waitForTransactionReceipt({ hash: regMerchHash });
  } catch (e: any) {
    console.log(`    Merchant registration: ${e.message?.slice(0, 100)}`);
  }

  // 6c. Approve AUDS spending
  console.log("    Approving AUDS for settlement contract...");
  const approveHash = await walletClient.sendTransaction({
    to: audsAddr,
    data: encodeFunctionData({
      abi: AUDS_ABI,
      functionName: "approve",
      args: [getAddress(settlementAddr), BigInt("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")],
    }),
  });
  console.log(`    Approved: ${approveHash}`);
  await publicClient.waitForTransactionReceipt({ hash: approveHash });

  // 6d. Submit settlement
  console.log("    Submitting settlement...");
  const settleHash = await walletClient.sendTransaction({
    to: settlementAddr,
    data: encodeFunctionData({
      abi: SETTLEMENT_ABI,
      functionName: "settle",
      args: [
        `0x${bytesToHex(result.cdol1Data)}` as Hex,
        `0x${result.pan}` as Hex,
        `0x${result.terminalId}` as Hex,
        `0x${bytesToHex(result.sigR)}` as Hex,
        `0x${bytesToHex(result.sigS)}` as Hex,
      ],
    }),
  });

  console.log(`    Settlement tx: ${settleHash}`);
  const receipt = await publicClient.waitForTransactionReceipt({ hash: settleHash });
  console.log(`    Status: ${receipt.status}`);
  console.log(`    Block: ${receipt.blockNumber}`);
  console.log(`    Explorer: https://explore.tempo.xyz/tx/${settleHash}`);
}

main().catch((err) => {
  console.error("Error:", err);
  process.exit(1);
});
