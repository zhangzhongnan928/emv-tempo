# CDOL1 Format Reference

## Fixed Layout (29 bytes)

The CDOL1 (Card Risk Management Data Object List 1) defines the exact byte layout
the terminal must send in the GENERATE AC command. The card hashes and signs these bytes.

| Offset | Length | EMV Tag | Field                      | Example              |
|--------|--------|---------|----------------------------|----------------------|
| 0      | 6      | 9F02    | Amount Authorized (BCD)    | `000000001000` = $10 |
| 6      | 6      | 9F03    | Amount Other (BCD)         | `000000000000`       |
| 12     | 2      | 9F1A    | Terminal Country Code      | `0036` (Australia)   |
| 14     | 5      | 95      | Terminal Verification Results | `0000000000`      |
| 19     | 2      | 5F2A    | Transaction Currency Code  | `0036` (AUD)         |
| 21     | 3      | 9A      | Transaction Date (YYMMDD)  | `260318`             |
| 24     | 1      | 9C      | Transaction Type           | `00` (purchase)      |
| 25     | 4      | 9F37    | Unpredictable Number       | random 4 bytes       |
| **Total** | **29** |      |                            |                      |

## CDOL1 DOL Encoding

The DOL (Data Object List) that the card returns in tag 8C tells the terminal
which tags to send and their lengths:

```
9F02 06  9F03 06  9F1A 02  95 05  5F2A 02  9A 03  9C 01  9F37 04
```

## BCD Amount Encoding

EMV amounts use Binary Coded Decimal (BCD), where each nibble (4 bits) represents
a decimal digit 0-9:

- `$10.00` = 1000 cents = `00 00 00 00 10 00`
- `$0.01`  = 1 cent     = `00 00 00 00 00 01`
- `$999.99` = 99999 cents = `00 00 00 09 99 99`

## Card Signature

The card signs: `SHA-256(raw 29 bytes of CDOL1 data)`

The signature is P-256 (secp256r1) ECDSA and returned in tag 9F4B as:
`r (32 bytes) || s (32 bytes)` = 64 bytes total.

## Currency Codes (ISO 4217 Numeric)

| Code   | Currency |
|--------|----------|
| `0036` | AUD      |
| `0840` | USD      |
| `0978` | EUR      |
