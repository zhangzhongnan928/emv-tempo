#!/bin/bash
# Build the EMV-Tempo Java Card applet
set -e

cd "$(dirname "$0")"

echo "=== Compiling EMV-Tempo Card Applet ==="
rm -rf build/classes
mkdir -p build/classes

javac -source 8 -target 8 \
  -cp lib/jcardsim.jar \
  -d build/classes \
  src/com/emvtempo/card/EMVTempoApplet.java

echo "Compiled successfully."

echo ""
echo "=== Creating CAP file ==="
mkdir -p build

# Create JAR from compiled classes
jar cf build/emvtempo-card.jar -C build/classes .

echo "JAR created: build/emvtempo-card.jar"
echo ""
echo "To install on J3R180 card:"
echo "  java -jar lib/gp.jar --install build/emvtempo-card.jar --default"
echo ""
echo "To list installed applets:"
echo "  java -jar lib/gp.jar --list"
echo ""
echo "To delete applet:"
echo "  java -jar lib/gp.jar --delete A000006690820001"
