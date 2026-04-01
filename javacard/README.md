# EMV-Tempo Physical Card (J3R180 Java Card)

Physical EMV contactless payment card applet for the NXP J3R180 Java Card. Works alongside the Android HCE card app — both can be registered simultaneously with different PANs.

## Card Details

| | Physical Card | HCE Card |
|---|---|---|
| **PAN** | `6690 8200 0000 0002` | `6690 8200 0000 0001` |
| **AID** | `A000006690820001` | `A000006690820001` |
| **Key** | P-256 on-card (never leaves) | P-256 in Android Keystore |
| **Platform** | J3R180 Java Card 3.0.4 | Android 8.0+ HCE |
| **ATC** | Persistent across power cycles | Session-only (resets on app restart) |

## Prerequisites

- **J3R180 card** (NXP JCOP 4, Java Card 3.0.4)
- **Contact smart card reader** (USB, PC/SC compatible)
- **Java 11** for CAP file compilation (JC 3.0.4 SDK requires JDK 11)
- **Java 17** for GlobalPlatformPro
- **Apache Ant** for the build
- **Oracle Java Card SDK 3.0.4** (auto-cloned to `/tmp/oracle_javacard_sdks`)

## Build & Install

```bash
# 1. Get the Java Card SDK (one-time)
git clone --depth 1 https://github.com/martinpaljak/oracle_javacard_sdks.git /tmp/oracle_javacard_sdks

# 2. Build the CAP file (requires JDK 11)
JAVA_HOME=$(/usr/libexec/java_home -v 11) ant -f build_cap.xml

# 3. Install on card (two-step process — see "GPP Bug Workaround" below)
#    Step 1: Load the CAP
java -jar lib/gp.jar --load build/emvtempo-card.cap \
  --key-enc <YOUR_ENC_KEY> --key-mac <YOUR_MAC_KEY> --key-dek <YOUR_DEK_KEY>

#    Step 2: Install the applet instance (via raw APDU with older GPP)
java -jar lib/gp-old.jar \
  -s 80E60C001F06A0000066908208A00000669082000108A000006690820001010002C90000 \
  --key-enc <YOUR_ENC_KEY> --key-mac <YOUR_MAC_KEY> --key-dek <YOUR_DEK_KEY>

# 4. Verify installation
java -jar lib/gp.jar -l --key-enc <YOUR_ENC_KEY> --key-mac <YOUR_MAC_KEY> --key-dek <YOUR_DEK_KEY>
```

## Lessons Learned (J3R180 Development)

### 1. GP Transport Keys Are Not Always Default

The standard GP default key (`404142434445464748494A4B4C4D4E4F`) did not work on our J3R180. The CPLC data showed `ICPrePersonalizer=165E` — a third party pre-personalized the card with diversified keys (CDKenc, CDKmac, CDKkek). The card vendor must provide these keys. **Do not brute-force keys** — JCOP4 cards may have a retry counter (typically 10 attempts) that permanently locks the card.

### 2. GlobalPlatformPro LOAD Bug on JCOP4

GPP v25.10.20 (and v24.10.15) have a bug where the `--install` command crashes with `Invalid argument: newPosition < 0: (-2 < 0)` after successfully loading the CAP file. The LOAD succeeds, but the INSTALL [for install] step hits a Java buffer underflow in GPP's APDU handling.

**Workaround**: Split into two steps:
1. **LOAD** with current GPP: `gp --load build/emvtempo-card.cap --key-enc ...`
2. **INSTALL** via raw APDU with older GPP: `gp-old -s 80E60C001F06...`

The raw INSTALL APDU format: `80 E6 0C 00 1F 06<pkgAID> 08<appletAID> 08<instanceAID> 01 00 02 C9 00 00`

### 3. Card State Must Be SECURED

Unfused J3R180 cards start in `OP_READY` state. Applet loading requires `SECURED` state. Use `gp --initialize-card` then `gp --secure-card` to transition (both require the correct keys).

### 4. Java Card Language Restrictions

Java Card uses a subset of Java. Key restrictions that caused build failures:
- **All array indices must be `short`**, not `int`. Cast every `.length` to `(short)` and every `new byte[expr]` to `new byte[(short)(expr)]`.
- **No `String`, `BigInteger`, or standard Java collections.** Use `byte[]` and `Util.arrayCopy`.
- **No `switch` on `byte` values** in some converters — use `if/else if` chains.
- The JC 3.0.4 SDK converter requires **JDK 11** (not 17). Build with `JAVA_HOME=$(/usr/libexec/java_home -v 11)`.

### 5. P-256 Curve Parameters Must Be Set Explicitly

Unlike Android Keystore which auto-configures P-256, Java Card requires setting all curve parameters manually on both the public and private key before calling `genKeyPair()`:
```java
ecPubKey.setFieldFP(P, ...);
ecPubKey.setA(A, ...);
ecPubKey.setB(B, ...);
ecPubKey.setG(G, ...);  // uncompressed generator point (65 bytes)
ecPubKey.setR(N, ...);
ecPubKey.setK((short)1);
// Same for ecPrivKey
kp.genKeyPair();  // ~300ms on J3R180
```

### 6. DER Signature Parsing

Java Card's `Signature.ALG_ECDSA_SHA_256` returns DER-encoded signatures, but the EMV settlement contract expects raw `r(32) || s(32)`. The applet must parse the DER structure and pad/trim r and s to exactly 32 bytes each. Watch for:
- Leading `0x00` padding on r or s (DER adds this when the high bit is set)
- Short r or s values (< 32 bytes) that need left-padding with zeros

### 7. APDU Format Must Exactly Match HCE

The terminal's `EMVDriver.kt` and the on-chain `EMVSettlement.sol` contract expect specific TLV tags:
- Public key: separate `9F47` (X) + `9F48` (Y) tags, **not** `9F46` (uncompressed point)
- CDOL1 DOL: tag `8C` must be present in READ RECORD
- Issuer country: tag `5F28` must be present
- GPO response: must use format 2 (tag `77` with `82`+`94`), **not** format 1 (tag `80`)
- GENERATE AC: must return raw `r||s` (64 bytes) in tag `9F4B`, not DER

## How It Works

The applet implements the same 4-step EMV APDU flow as the HCE card:

1. **SELECT** — Terminal selects AID, card returns FCI with AID + PDOL
2. **GET PROCESSING OPTIONS** — Card returns AIP + AFL in template 77
3. **READ RECORD** — Card returns Track 2, PAN, expiry, issuer country, CDOL1 DOL, P-256 public key (X in 9F47, Y in 9F48)
4. **GENERATE AC** — Card signs SHA-256(CDOL1) with on-card P-256 key, returns ARQC + ATC + cryptogram + raw r||s signature (64 bytes)

The terminal and on-chain settlement contract don't know (or care) whether the card is physical or HCE — the APDU flow and signature verification are identical.

## Project Structure

```
javacard/
├── src/com/emvtempo/card/
│   └── EMVTempoApplet.java     # Java Card applet (full EMV APDU flow)
├── build/
│   └── emvtempo-card.cap       # Compiled CAP file
├── lib/
│   ├── gp.jar                  # GlobalPlatformPro v25.10.20 (for LOAD + list)
│   ├── gp-old.jar              # GlobalPlatformPro v24.10.15 (for INSTALL workaround)
│   ├── ant-javacard.jar         # ant-javacard build task
│   └── jcardsim.jar            # JC API stubs (for javac compilation)
├── build_cap.xml               # Ant build file (uses JC 3.0.4 SDK)
├── build.sh                    # Simple build script
├── install.sh                  # Card installation script
├── read-pubkey.sh              # Read public key from installed card
└── README.md
```
