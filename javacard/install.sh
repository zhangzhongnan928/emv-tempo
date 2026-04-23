#!/bin/bash
# Install EMV-Tempo applet onto J3R180 Java Card
#
# Prerequisites:
#   - Contact card reader connected via USB
#   - J3R180 card inserted in reader
#   - Java 11+ installed
#
# Usage:
#   ./install.sh
set -e

cd "$(dirname "$0")"
GP="java -jar lib/gp.jar"

echo "=== EMV-Tempo Card Installer ==="
echo ""

# Check reader
echo "1. Checking card reader..."
$GP --list 2>&1 || {
    echo "ERROR: No card reader detected or no card inserted."
    echo "  - Insert J3R180 card into the contact reader"
    echo "  - Check USB connection"
    exit 1
}
echo ""

# Build if needed
if [ ! -f build/emvtempo-card.jar ]; then
    echo "2. Building applet..."
    bash build.sh
else
    echo "2. Using existing build/emvtempo-card.jar"
fi
echo ""

# Delete existing applet (ignore errors if not present)
echo "3. Removing old applet (if present)..."
$GP --delete A000006690820001 2>/dev/null || true
echo ""

# Install
echo "4. Installing EMV-Tempo applet..."
$GP --install build/emvtempo-card.jar \
    --applet A000006690820001 \
    --package A00000669082 \
    --default
echo ""

echo "5. Verifying installation..."
$GP --list
echo ""

echo "=== Installation Complete ==="
echo ""
echo "The card is now ready for tap-to-pay."
echo ""
echo "PAN: 6690 8200 0000 0002"
echo "AID: A000006690820001"
echo ""
echo "Next steps:"
echo "  1. Read the public key from the card (tap on terminal or use read-pubkey.sh)"
echo "  2. Register the card on-chain with the public key"
echo "  3. Fund the cardholder wallet with AUDM tokens"
echo "  4. Tap the card on the OPK terminal to pay"
