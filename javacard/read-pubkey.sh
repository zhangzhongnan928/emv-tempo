#!/bin/bash
# Read the P-256 public key from an installed EMV-Tempo card
#
# Sends SELECT + READ RECORD APDUs to extract the public key
# coordinates needed for on-chain registration.
#
# Requires: card reader + card inserted
set -e

cd "$(dirname "$0")"
GP="java -jar lib/gp.jar"

echo "=== Reading EMV-Tempo Card Public Key ==="
echo ""

# SELECT AID
echo "Sending SELECT..."
SELECT_RESP=$($GP --apdu 00A4040008A000006690820001 2>&1)
echo "$SELECT_RESP"
echo ""

# GET PROCESSING OPTIONS
echo "Sending GPO..."
GPO_RESP=$($GP --apdu 80A80000023100 2>&1)
echo "$GPO_RESP"
echo ""

# READ RECORD (SFI 1, Record 1)
echo "Sending READ RECORD..."
RR_RESP=$($GP --apdu 00B2010C00 2>&1)
echo "$RR_RESP"
echo ""

echo "=== Parse the READ RECORD response above ==="
echo "Look for tags:"
echo "  9F47 (32 bytes) = Public Key X"
echo "  9F48 (32 bytes) = Public Key Y"
echo ""
echo "Use these values to register the card on-chain:"
echo "  PUB_KEY_X=0x<9F47 value>"
echo "  PUB_KEY_Y=0x<9F48 value>"
