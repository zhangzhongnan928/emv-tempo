# EMV-to-Tempo PoC

Settles EMV contactless card payments on the Tempo blockchain. A cardholder taps a phone (Android HCE simulating an EMV card) on a standard EMV terminal (iMin Swift 2). The terminal submits the raw EMV blob on-chain. A Solidity contract parses the TLV data, verifies the P-256 signature via the Daimo P-256 verifier, and transfers AUDS tokens from cardholder to merchant.

## Architecture

```
┌──────────────┐    NFC/ISO 14443    ┌──────────────┐
│  iMin Swift 2 │◄──────────────────►│  Google Pixel │
│  (Terminal)    │  EMV APDUs (~170ms)│  (HCE Card)   │
│  Android 13    │                    │  P-256 key    │
│  NFC Reader    │                    │  No internet  │
└──────┬─────────┘                    └───────────────┘
       │
       │ After EMV flow completes:
       │ terminal has: CDOL1 data + P-256 signature
       │
       ▼
┌──────────────┐      Tempo JSON-RPC       ┌──────────────┐
│  Terminal      │─────────────────────────►│  Tempo Testnet│
│  submits       │  EMVSettlement.settle()  │  Chain 42431  │
│  settlement    │                          │               │
└──────────────┘                           │  Contracts:   │
                                           │  - AUDS token │
                                           │  - EMVSettle  │
                                           │  - CardReg    │
                                           │  - MerchantReg│
                                           └───────────────┘
```

## How It Works — End to End

### The Payment Flow (What Happens When You Tap)

1. **Merchant enters amount** on the iMin Swift 2 terminal app and taps "CHARGE"
2. **Terminal enters NFC reader mode**, displaying "Tap Card"
3. **Cardholder holds phone** (running HCE app) near the terminal's NFC reader
4. **EMV contactless exchange** happens over NFC in ~170ms (no internet on the phone):
   - Terminal sends **SELECT** with AID `A000006690820001` → Card returns FCI with PDOL
   - Terminal sends **GET PROCESSING OPTIONS** with amount/currency/random number → Card returns AIP + AFL
   - Terminal sends **READ RECORD** → Card returns PAN, P-256 public key, CDOL1 format
   - Terminal sends **GENERATE AC** with 29-byte CDOL1 data → Card signs `SHA-256(CDOL1)` with its hardware-backed P-256 key and returns the signature
5. **Phone can be removed** — NFC exchange is complete
6. **Terminal submits on-chain**: calls `EMVSettlement.settle(cdol1Data, pan, terminalId, sigR, sigS)` on Tempo testnet
7. **Smart contract verifies**:
   - Parses the BCD-encoded amount from CDOL1 data
   - Validates the currency is AUD (ISO 4217: 036)
   - Checks replay protection (unpredictable number + date + PAN)
   - Verifies the P-256 signature using the Daimo P-256 verifier (~330k gas)
   - Looks up the cardholder address from CardRegistry (by PAN)
   - Looks up the merchant address from MerchantRegistry (by terminal ID)
   - Transfers AUDS from cardholder to merchant
8. **Transaction confirmed** in ~3 seconds (Tempo deterministic finality)
9. **Terminal displays result** with transaction hash and explorer link

### What the Card (Phone) Does

The HCE app behaves identically to a physical EMV JavaCard:

- Stores a P-256 keypair in Android Keystore (hardware-backed, non-extractable)
- Responds to standard EMV APDU commands over NFC
- Signs transaction data with its private key
- **Never uses the internet during a payment** — this proves the architecture works with real physical cards
- The only internet use is a one-time registration to link the card's public key to a Tempo address

### What the Terminal Does

The terminal app drives the payment flow:

- Provides a numeric keypad for amount entry
- Uses `NfcAdapter.enableReaderMode()` to act as an EMV reader
- Constructs CDOL1 data (amount, currency, date, random number)
- Sends the full EMV APDU sequence to the card
- Extracts the P-256 signature from the card's response
- Submits the settlement transaction to Tempo testnet using web3j
- Displays the result with a link to the block explorer

### What the Smart Contracts Do

| Contract | Purpose |
|----------|---------|
| **EMVSettlement** | Core contract: parses CDOL1, verifies P-256 sig, transfers AUDS |
| **CardRegistry** | Maps PAN → P-256 public key → cardholder Tempo address |
| **MerchantRegistry** | Maps terminal ID → merchant Tempo address |
| **AUDS** | TIP-20 stablecoin (Australian Dollar Stablecoin, 6 decimals) |

---

## Deployed Contracts (Tempo Moderato Testnet)

| Contract | Address |
|----------|---------|
| AUDS Token (TIP-20) | `0x20c000000000000000000000cd79b57f59673048` |
| CardRegistry | `0xE133e31a3DC627Bd59DeC6a358C640E22b288799` |
| MerchantRegistry | `0xa4B5076466e7Dd1269a6F5C46D72341c2F899844` |
| EMVSettlement | `0xABcCc21F466ebd68494255790021c718B9DBF81d` |
| P-256 Verifier (Daimo) | `0xc2b78104907F722DABAc4C69f826a522B2754De4` |

Deployer / Cardholder / Merchant: `0x54235780057CC828C92aA40e3b02053881990153`

---

## Setup Guide

### Prerequisites

- **Foundry** (forge, cast) — for smart contract compilation and deployment
- **Node.js 18+** — for the CLI simulator
- **Android SDK** (API 34) — for building the Android apps
- **Two Android devices**: one NFC-capable phone (card), one NFC reader terminal
- **pathUSD** on Tempo testnet (from https://faucet.tempo.xyz) to pay gas

### Step 1: Build and Test Smart Contracts

```bash
cd contracts
forge build
forge test    # 32 tests, all should pass
```

### Step 2: Run the CLI Simulator (No Hardware Needed)

This tests the entire flow locally without Android devices:

```bash
cd simulator
npm install
npm run e2e
```

You should see:
- P-256 keypair generated
- Full EMV APDU exchange (SELECT → GPO → READ RECORD → GENERATE AC)
- Signature verified locally

### Step 3: Deploy to Tempo Testnet

**3a. Get testnet gas**

Visit https://faucet.tempo.xyz and request pathUSD for your deployer address.

**3b. Create the AUDS token**

```bash
cd contracts
# Create AUDS via TIP-20 Factory (quoteToken = pathUSD, admin = deployer)
cast send 0x20Fc000000000000000000000000000000000000 \
  "createToken(string,string,string,address,address,bytes32)" \
  "Australian Dollar Stablecoin" "AUDS" "AUD" \
  0x20c0000000000000000000000000000000000000 \
  <YOUR_DEPLOYER_ADDRESS> \
  0x0000000000000000000000000000000000000000000000000000000000000001 \
  --rpc-url https://rpc.moderato.tempo.xyz \
  --private-key <YOUR_PRIVATE_KEY>
```

Note the AUDS token address from the transaction logs.

**3c. Deploy settlement contracts**

```bash
# Deploy CardRegistry
forge create src/CardRegistry.sol:CardRegistry \
  -r https://rpc.moderato.tempo.xyz \
  --private-key <KEY> --broadcast

# Deploy MerchantRegistry
forge create src/MerchantRegistry.sol:MerchantRegistry \
  -r https://rpc.moderato.tempo.xyz \
  --private-key <KEY> --broadcast

# Deploy EMVSettlement (use Daimo P256 verifier: 0xc2b78104907F722DABAc4C69f826a522B2754De4)
forge create src/EMVSettlement.sol:EMVSettlement \
  --constructor-args <CARD_REGISTRY> <MERCHANT_REGISTRY> <AUDS_TOKEN> \
  0xc2b78104907F722DABAc4C69f826a522B2754De4 \
  -r https://rpc.moderato.tempo.xyz \
  --private-key <KEY> --broadcast
```

**3d. Set up roles and fund accounts**

```bash
# Grant ISSUER_ROLE to mint AUDS
ISSUER_ROLE=$(cast call <AUDS_TOKEN> "ISSUER_ROLE()(bytes32)" --rpc-url https://rpc.moderato.tempo.xyz)
cast send <AUDS_TOKEN> "grantRole(bytes32,address)" $ISSUER_ROLE <DEPLOYER> \
  --rpc-url https://rpc.moderato.tempo.xyz --private-key <KEY>

# Mint AUDS to the cardholder (e.g. 10,000 AUDS = 10000000000 with 6 decimals)
cast send <AUDS_TOKEN> "mint(address,uint256)" <CARDHOLDER> 10000000000 \
  --rpc-url https://rpc.moderato.tempo.xyz --private-key <KEY>

# Approve EMVSettlement to spend cardholder's AUDS
cast send <AUDS_TOKEN> "approve(address,uint256)" <SETTLEMENT_CONTRACT> \
  0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  --rpc-url https://rpc.moderato.tempo.xyz --private-key <KEY>

# Register merchant terminal
cast send <MERCHANT_REGISTRY> "registerMerchant(bytes8,address)" \
  0x5445524D30303100 <MERCHANT_ADDRESS> \
  --rpc-url https://rpc.moderato.tempo.xyz --private-key <KEY>
```

### Step 4: Build and Install the HCE Card App (Phone)

**4a. Build the APK**

```bash
cd android/hce-card
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
```

**4b. Install on the phone**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**4c. Launch and generate key**

Open "AUDS Card" on the phone. The app will:
- Generate a P-256 keypair in Android Keystore
- Display the PAN (`6690 8200 0000 0001`) and public key coordinates

**4d. Register the card on-chain**

The card's public key must be registered in CardRegistry. You can either:

- Use the app's built-in registration form (enter RPC URL, contract address, private key, cardholder address)
- Or register via cast:

```bash
# Get pubkey X and Y from the app's logcat output
adb logcat -s "EMV_CARD_KEY" -d

# Register the card
cast send <CARD_REGISTRY> "registerCard(bytes8,bytes32,bytes32,address)" \
  0x6690820000000001 <PUBKEY_X> <PUBKEY_Y> <CARDHOLDER_ADDRESS> \
  --rpc-url https://rpc.moderato.tempo.xyz --private-key <KEY>
```

### Step 5: Build and Install the Terminal App (iMin Swift 2)

**5a. Configure contract addresses**

Edit `android/terminal/app/src/main/java/com/emvtempo/terminal/TempoSubmitter.kt`:
- Set `DEFAULT_CONTRACT_ADDRESS` to your EMVSettlement address
- Set `DEFAULT_PRIVATE_KEY` to the terminal's wallet private key (must hold pathUSD for gas)

**5b. Build and install**

```bash
cd android/terminal
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 6: Make a Payment

1. Open the **Terminal app** on the iMin Swift 2
2. Enter a dollar amount (e.g. `10.00`)
3. Tap **CHARGE** — the screen shows "Tap Card"
4. Hold the **phone** (with HCE app running) near the terminal's NFC reader
5. Keep the phone steady for ~1 second
6. The terminal will show:
   - "Reading Card..." → EMV APDU exchange happening
   - "Card read complete. Submitting to chain..." → NFC done, submitting tx
   - **"Payment Settled"** → success with tx hash and explorer link
7. Tap the explorer link to view the transaction on https://explore.tempo.xyz

### Troubleshooting

| Issue | Fix |
|-------|-----|
| "Tag was lost" | Hold phone closer/steadier to the terminal. The NFC exchange needs ~200ms. |
| Card app crashes on tap | Check `adb logcat -s "HCEService" "AndroidRuntime"` for the crash trace. |
| "Terminal not registered" | Register the terminal in MerchantRegistry (see Step 3d). |
| "Card not registered" | Register the card's pubkey in CardRegistry (see Step 4d). |
| "Invalid signature" / out of gas | Ensure gas limit is at least 1,500,000 (Daimo verifier uses ~330k gas). |
| "Already settled" | Replay protection — each (PAN + unpredictable number + date) can only settle once. Tap again for a new transaction. |
| "Not AUD" | The terminal must send currency code `0x0036` (AUD) in the CDOL1 data. |
| "Transfer failed" | Cardholder needs AUDS balance and must have approved EMVSettlement to spend. |
| HCE app not responding to NFC | Ensure the HCE app is open. Check AID routing: `adb logcat -s "RegisteredAidCache"`. If Google Pay intercepts, change AID category to "other" in `apduservice.xml`. |

---

## Project Structure

```
contracts/                      Solidity (Foundry)
├── src/
│   ├── P256Check.sol           RIP-7212 availability test
│   ├── CardRegistry.sol        PAN → pubkey → cardholder address
│   ├── MerchantRegistry.sol    Terminal ID → merchant address
│   ├── EMVSettlement.sol       Core: CDOL1 parsing + P256 verify + AUDS transfer
│   └── interfaces/ITIP20.sol   TIP-20 token interface
├── test/                       32 tests (all passing)
│   ├── EMVSettlement.t.sol     17 tests: happy path, replay, bad sig, edge cases
│   ├── CardRegistry.t.sol      8 tests: register, deactivate, duplicates
│   ├── MerchantRegistry.t.sol  5 tests: register, update, zero address
│   └── P256Check.t.sol         2 tests: mock precompile
├── script/
│   ├── Deploy.s.sol            Deploy all contracts
│   ├── CreateAUDS.s.sol        Create AUDS via TIP-20 Factory
│   └── RegisterCard.s.sol      Register a test card
└── foundry.toml

simulator/                      TypeScript CLI simulator
├── src/
│   ├── p256.ts                 P-256 keygen + signing (@noble/curves)
│   ├── tlv.ts                  BER-TLV encoder/decoder
│   ├── card-sim.ts             EMV card APDU responder
│   ├── terminal-sim.ts         EMV terminal APDU driver
│   └── e2e-test.ts             Full flow: card → terminal → Tempo
├── package.json
└── tsconfig.json

android/
├── hce-card/                   HCE card app (the "phone card")
│   └── app/src/main/java/com/emvtempo/hcecard/
│       ├── HCEService.kt       HostApduService: SELECT, GPO, READ RECORD, GENERATE AC
│       ├── EMVResponder.kt     TLV encoding utilities
│       ├── P256KeyManager.kt   Android Keystore P-256 key management
│       ├── CardRegistration.kt One-time on-chain card registration
│       └── MainActivity.kt     Card info display + registration UI
│
└── terminal/                   Terminal app (iMin Swift 2)
    └── app/src/main/java/com/emvtempo/terminal/
        ├── TerminalActivity.kt NFC reader + amount input + result display
        ├── EMVDriver.kt        APDU builder + EMV flow driver
        ├── TLVParser.kt        BER-TLV parser
        └── TempoSubmitter.kt   Submits settle() tx to Tempo via web3j

docs/
└── CDOL1-FORMAT.md             CDOL1 byte layout reference (29 bytes)
```

## EMV APDU Flow Detail

```
Terminal                              Card (Phone)
   │                                     │
   │──── SELECT (AID A000006690820001) ───►│
   │◄─── FCI: AID + PDOL definition ────│
   │                                     │
   │──── GPO (amount, currency, UN) ────►│
   │◄─── AIP (3900) + AFL (SFI1,R1) ───│
   │                                     │
   │──── READ RECORD (SFI1, Rec1) ─────►│
   │◄─── PAN + PubKey(X,Y) + CDOL1 DOL │
   │                                     │
   │──── GENERATE AC (29B CDOL1 data) ──►│
   │                 Card computes:       │
   │                 hash = SHA256(CDOL1) │
   │                 (r,s) = Sign(hash)   │
   │◄─── CID + ATC + Cryptogram + Sig ──│
   │                                     │
   │  NFC complete (~170ms)              │
   │                                     │
   ▼
 Terminal submits settle() to Tempo
```

## CDOL1 Data Format (29 bytes)

| Offset | Len | Tag  | Field                    | Example            |
|--------|-----|------|--------------------------|--------------------|
| 0      | 6   | 9F02 | Amount Authorized (BCD)  | `000000001000` = $10 |
| 6      | 6   | 9F03 | Amount Other             | `000000000000`     |
| 12     | 2   | 9F1A | Terminal Country Code    | `0036` (Australia) |
| 14     | 5   | 95   | TVR                      | `0000000000`       |
| 19     | 2   | 5F2A | Transaction Currency     | `0036` (AUD)       |
| 21     | 3   | 9A   | Transaction Date (BCD)   | `260318`           |
| 24     | 1   | 9C   | Transaction Type         | `00` (purchase)    |
| 25     | 4   | 9F37 | Unpredictable Number     | random 4 bytes     |

The card signs `SHA-256(these 29 bytes)` with its P-256 private key.

## IIN / BIN and AID

| Field | Value |
|-------|-------|
| IIN/BIN | `66908200` (OpenPasskey Pty Ltd) |
| Issuer | CUSIP Global Services via Pay.UK sponsorship |
| Effective | July 1, 2026 |
| PAN | `6690 8200 0000 0001` (first test card) |
| AID | `A000006690820001` |

### AID Derivation

```
A0 00006690820001
│  │            │
│  │            └─ PIX (application identifier, first app)
│  └─────────────── IIN 66908200 zero-padded to 5 bytes
└──────────────────── Proprietary RID namespace prefix
```

### PAN Structure

```
6690 8200 XXXX XXXX C
│         │          │
IIN       Card seq   Luhn check digit (not enforced in PoC)
(8 dig)   (up to 11)
```

## Tempo Testnet

| Property | Value |
|----------|-------|
| Network Name | Tempo Testnet (Moderato) |
| Chain ID | 42431 |
| RPC | https://rpc.moderato.tempo.xyz |
| Explorer | https://explore.tempo.xyz |
| Faucet | https://faucet.tempo.xyz |
| Block Time | ~0.5 seconds |
| Finality | Deterministic (Simplex BFT) |
| Gas Token | None (fees paid in TIP-20 stablecoins) |

## Known Limitations (PoC Scope)

- **Terminal ID not signed**: The `terminalId` parameter is not in the CDOL1 data, so it's not covered by the card's signature. A malicious relay could redirect funds. Production would use caller whitelists or extended CDOL1.
- **Permissionless registration**: Anyone can register cards and merchants. Production would restrict to authorized issuers.
- **Single PAN hardcoded**: The HCE app uses PAN `6690820000000001`. Production would support multiple cards.
- **No RIP-7212**: Tempo testnet doesn't have the P-256 precompile, so we use the Daimo Solidity fallback (~330k gas vs ~3.4k). This makes settlement more expensive but functionally identical.
- **Deployer = cardholder = merchant**: For the PoC, the same address plays all roles.

## Verified Transactions

| Amount | TX Hash | Block |
|--------|---------|-------|
| $10.00 AUD | [`0xd8929f...fdaca`](https://explore.tempo.xyz/tx/0xd8929f21e2eb58529f65d35d3194994c2a04598d0fac229899b76d35644fdaca) | 8843583 |
