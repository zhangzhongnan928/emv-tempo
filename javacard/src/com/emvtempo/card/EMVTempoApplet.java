package com.emvtempo.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.*;

/**
 * EMV-Tempo Physical Card Applet for J3R180.
 *
 * Implements the EXACT same APDU flow and TLV format as HCEService.kt
 * so the OPK Pay Terminal and EMVSettlement contract work identically
 * with both the physical card and the Android HCE card.
 *
 * APDU flow: SELECT -> GPO -> READ RECORD -> GENERATE AC
 * PAN: 6690820000000002 (HCE uses 0001)
 * Key: P-256 generated on-card, private key never leaves J3R180
 */
public class EMVTempoApplet extends Applet {

    // JC 3.0.4 constants (may be missing from older API stubs)
    private static final short LENGTH_EC_FP_256 = 256;
    private static final byte ALG_ECDSA_SHA_256 = 33;

    // AID: A000006690820001
    private static final byte[] AID = {
        (byte)0xA0, 0x00, 0x00, 0x66, (byte)0x90, (byte)0x82, 0x00, 0x01
    };

    // PAN: 6690820000000002
    private static final byte[] PAN = {
        0x66, (byte)0x90, (byte)0x82, 0x00, 0x00, 0x00, 0x00, 0x02
    };

    // Expiry: YYMMDD BCD = 261231 (matches HCE)
    private static final byte[] EXPIRY = { 0x26, 0x12, 0x31 };

    // Issuer country: Australia = 0036 (matches HCE)
    private static final byte[] ISSUER_COUNTRY = { 0x00, 0x36 };

    // Track 2: PAN + D + YYMM + ServiceCode(201) + discretionary + FF
    // Matches HCE format exactly
    private static final byte[] TRACK2 = {
        0x66, (byte)0x90, (byte)0x82, 0x00, 0x00, 0x00, 0x00, 0x02,
        (byte)0xD2, 0x61, 0x22, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x00, (byte)0xFF
    };

    // CDOL1 DOL (tag 8C): matches HCE exactly
    // 9F02(6)+9F03(6)+9F1A(2)+95(5)+5F2A(2)+9A(3)+9C(1)+9F37(4) = 29 bytes
    private static final byte[] CDOL1_DOL = {
        (byte)0x9F, 0x02, 0x06,
        (byte)0x9F, 0x03, 0x06,
        (byte)0x9F, 0x1A, 0x02,
        (byte)0x95, 0x05,
        0x5F, 0x2A, 0x02,
        (byte)0x9A, 0x03,
        (byte)0x9C, 0x01,
        (byte)0x9F, 0x37, 0x04
    };

    private static final short CDOL1_DATA_LENGTH = 29;

    // Application label = "AUDS PAY" (matches HCE)
    private static final byte[] APP_LABEL = {
        'A', 'U', 'D', 'S', ' ', 'P', 'A', 'Y'
    };

    // PDOL: matches HCE (9F66(4) + 9F02(6) + 5F2A(2) + 9F37(4))
    private static final byte[] PDOL = {
        (byte)0x9F, 0x66, 0x04, 0x00,
        (byte)0x9F, 0x02, 0x06, 0x00,
        0x5F, 0x2A, 0x02, 0x00,
        (byte)0x9F, 0x37, 0x04, 0x00
    };

    // AIP: 3900 (matches HCE)
    private static final byte[] EMV_AIP = { 0x39, 0x00 };

    // AFL: 08010100 (SFI 1, record 1) (matches HCE)
    private static final byte[] AFL = { 0x08, 0x01, 0x01, 0x00 };

    // P-256 curve parameters (NIST P-256 / secp256r1)
    private static final byte[] EC_FP = {
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,0x00,0x00,0x00,0x01,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
    };
    private static final byte[] EC_A = {
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,0x00,0x00,0x00,0x01,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFC
    };
    private static final byte[] EC_B = {
        0x5A,(byte)0xC6,0x35,(byte)0xD8,(byte)0xAA,0x3A,(byte)0x93,(byte)0xE7,
        (byte)0xB3,(byte)0xEB,(byte)0xBD,0x55,0x76,(byte)0x98,(byte)0x86,(byte)0xBC,
        0x65,0x1D,0x06,(byte)0xB0,(byte)0xCC,0x53,(byte)0xB0,(byte)0xF6,
        0x3B,(byte)0xCE,0x3C,0x3E,0x27,(byte)0xD2,0x60,0x4B
    };
    private static final byte[] EC_G = {
        0x04,
        0x6B,0x17,(byte)0xD1,(byte)0xF2,(byte)0xE1,0x2C,0x42,0x47,
        (byte)0xF8,(byte)0xBC,(byte)0xE6,(byte)0xE5,0x63,(byte)0xA4,0x40,(byte)0xF2,
        0x77,0x03,0x7D,(byte)0x81,0x2D,(byte)0xEB,0x33,(byte)0xA0,
        (byte)0xF4,(byte)0xA1,0x39,0x45,(byte)0xD8,(byte)0x98,(byte)0xC2,(byte)0x96,
        0x4F,(byte)0xE3,0x42,(byte)0xE2,(byte)0xFE,0x1A,0x7F,(byte)0x9B,
        (byte)0x8E,(byte)0xE7,(byte)0xEB,0x4A,0x7C,0x0F,(byte)0x9E,0x16,
        0x2B,(byte)0xCE,0x33,0x57,0x6B,0x31,0x5E,(byte)0xCE,
        (byte)0xCB,(byte)0xB6,0x40,0x68,0x37,(byte)0xBF,0x51,(byte)0xF5
    };
    private static final byte[] EC_N = {
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,0x00,0x00,0x00,0x00,
        (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
        (byte)0xBC,(byte)0xE6,(byte)0xFA,(byte)0xAD,(byte)0xA7,0x17,(byte)0x9E,(byte)0x84,
        (byte)0xF3,(byte)0xB9,(byte)0xCA,(byte)0xC2,(byte)0xFC,0x63,0x25,0x51
    };

    // Instance fields
    private ECPrivateKey ecPrivKey;
    private Signature ecdsaSigner;
    private MessageDigest sha256;
    private byte[] pubKeyX;  // 32 bytes
    private byte[] pubKeyY;  // 32 bytes
    private byte[] sigBuf;   // DER signature scratch
    private byte[] scratch;  // general scratch
    private short atc;

    protected EMVTempoApplet() {
        pubKeyX = new byte[(short)32];
        pubKeyY = new byte[(short)32];
        sigBuf = new byte[(short)80];
        scratch = new byte[(short)128];
        atc = (short)0;

        // Generate P-256 keypair with explicit curve params
        KeyPair kp = new KeyPair(KeyPair.ALG_EC_FP, LENGTH_EC_FP_256);
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        ecPrivKey = (ECPrivateKey) kp.getPrivate();

        pub.setFieldFP(EC_FP, (short)0, (short)EC_FP.length);
        pub.setA(EC_A, (short)0, (short)EC_A.length);
        pub.setB(EC_B, (short)0, (short)EC_B.length);
        pub.setG(EC_G, (short)0, (short)EC_G.length);
        pub.setR(EC_N, (short)0, (short)EC_N.length);
        pub.setK((short)1);

        ecPrivKey.setFieldFP(EC_FP, (short)0, (short)EC_FP.length);
        ecPrivKey.setA(EC_A, (short)0, (short)EC_A.length);
        ecPrivKey.setB(EC_B, (short)0, (short)EC_B.length);
        ecPrivKey.setG(EC_G, (short)0, (short)EC_G.length);
        ecPrivKey.setR(EC_N, (short)0, (short)EC_N.length);
        ecPrivKey.setK((short)1);

        kp.genKeyPair();

        // Extract X, Y from uncompressed point (04 || X || Y)
        byte[] w = new byte[(short)65];
        pub.getW(w, (short)0);
        Util.arrayCopyNonAtomic(w, (short)1, pubKeyX, (short)0, (short)32);
        Util.arrayCopyNonAtomic(w, (short)33, pubKeyY, (short)0, (short)32);

        ecdsaSigner = Signature.getInstance(ALG_ECDSA_SHA_256, false);
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new EMVTempoApplet().register(AID, (short)0, (byte)AID.length);
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            sendSelect(apdu);
            return;
        }
        byte[] buf = apdu.getBuffer();
        byte cla = buf[ISO7816.OFFSET_CLA];
        byte ins = buf[ISO7816.OFFSET_INS];

        if (cla == (byte)0x80 && ins == (byte)0xA8) {
            sendGPO(apdu);
        } else if (cla == (byte)0x00 && ins == (byte)0xB2) {
            sendReadRecord(apdu);
        } else if (cla == (byte)0x80 && ins == (byte)0xAE) {
            sendGenerateAC(apdu);
        } else {
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // ---- SELECT: FCI with AID + PDOL (matches HCE) ----
    private void sendSelect(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short off = (short)0;

        // Build A5 contents in scratch: 50(label) + 9F38(PDOL)
        short a5off = (short)0;
        scratch[a5off++] = 0x50;
        scratch[a5off++] = (byte)APP_LABEL.length;
        a5off = Util.arrayCopyNonAtomic(APP_LABEL, (short)0, scratch, a5off, (short)APP_LABEL.length);

        scratch[a5off++] = (byte)0x9F;
        scratch[a5off++] = 0x38;
        scratch[a5off++] = (byte)PDOL.length;
        a5off = Util.arrayCopyNonAtomic(PDOL, (short)0, scratch, a5off, (short)PDOL.length);

        // Build 6F: 84(AID) + A5(a5data)
        // Inner: 84 + len + AID + A5 + len + a5data
        short innerLen = (short)(2 + (short)AID.length + 2 + a5off);

        buf[off++] = 0x6F;
        buf[off++] = (byte)innerLen;

        buf[off++] = (byte)0x84;
        buf[off++] = (byte)AID.length;
        off = Util.arrayCopyNonAtomic(AID, (short)0, buf, off, (short)AID.length);

        buf[off++] = (byte)0xA5;
        buf[off++] = (byte)a5off;
        off = Util.arrayCopyNonAtomic(scratch, (short)0, buf, off, a5off);

        apdu.setOutgoingAndSend((short)0, off);
    }

    // ---- GPO: 77 [ 82(AIP) + 94(AFL) ] (matches HCE format 2) ----
    private void sendGPO(APDU apdu) {
        apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        short off = (short)0;

        short innerLen = (short)(2 + (short)EMV_AIP.length + 2 + (short)AFL.length);
        buf[off++] = 0x77;
        buf[off++] = (byte)innerLen;

        buf[off++] = (byte)0x82;
        buf[off++] = (byte)EMV_AIP.length;
        off = Util.arrayCopyNonAtomic(EMV_AIP, (short)0, buf, off, (short)EMV_AIP.length);

        buf[off++] = (byte)0x94;
        buf[off++] = (byte)AFL.length;
        off = Util.arrayCopyNonAtomic(AFL, (short)0, buf, off, (short)AFL.length);

        apdu.setOutgoingAndSend((short)0, off);
    }

    // ---- READ RECORD: 70 [ 57 + 5A + 5F24 + 5F28 + 8C + 9F47 + 9F48 ] ----
    // Matches HCE exactly: separate X/Y tags, includes CDOL1 DOL and issuer country
    private void sendReadRecord(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short off = (short)0;

        buf[off++] = 0x70;
        short lenPos = off;
        buf[off++] = 0x00; // placeholder, will use 81 XX if > 127

        // 57: Track 2
        buf[off++] = 0x57;
        buf[off++] = (byte)TRACK2.length;
        off = Util.arrayCopyNonAtomic(TRACK2, (short)0, buf, off, (short)TRACK2.length);

        // 5A: PAN
        buf[off++] = 0x5A;
        buf[off++] = (byte)PAN.length;
        off = Util.arrayCopyNonAtomic(PAN, (short)0, buf, off, (short)PAN.length);

        // 5F24: Expiry
        buf[off++] = 0x5F;
        buf[off++] = 0x24;
        buf[off++] = (byte)EXPIRY.length;
        off = Util.arrayCopyNonAtomic(EXPIRY, (short)0, buf, off, (short)EXPIRY.length);

        // 5F28: Issuer country
        buf[off++] = 0x5F;
        buf[off++] = 0x28;
        buf[off++] = (byte)ISSUER_COUNTRY.length;
        off = Util.arrayCopyNonAtomic(ISSUER_COUNTRY, (short)0, buf, off, (short)ISSUER_COUNTRY.length);

        // 8C: CDOL1 DOL
        buf[off++] = (byte)0x8C;
        buf[off++] = (byte)CDOL1_DOL.length;
        off = Util.arrayCopyNonAtomic(CDOL1_DOL, (short)0, buf, off, (short)CDOL1_DOL.length);

        // 9F47: Public Key X (32 bytes)
        buf[off++] = (byte)0x9F;
        buf[off++] = 0x47;
        buf[off++] = 0x20;
        off = Util.arrayCopyNonAtomic(pubKeyX, (short)0, buf, off, (short)32);

        // 9F48: Public Key Y (32 bytes)
        buf[off++] = (byte)0x9F;
        buf[off++] = 0x48;
        buf[off++] = 0x20;
        off = Util.arrayCopyNonAtomic(pubKeyY, (short)0, buf, off, (short)32);

        // Fill tag 70 length (use 81 XX for lengths > 127)
        short bodyLen = (short)(off - lenPos - 1);
        if (bodyLen > (short)127) {
            // Shift everything right by 1 to make room for 81 prefix
            Util.arrayCopyNonAtomic(buf, (short)(lenPos + 1), buf, (short)(lenPos + 2), bodyLen);
            buf[lenPos] = (byte)0x81;
            buf[(short)(lenPos + 1)] = (byte)bodyLen;
            off++;
        } else {
            buf[lenPos] = (byte)bodyLen;
        }

        apdu.setOutgoingAndSend((short)0, off);
    }

    // ---- GENERATE AC: sign CDOL1 with P-256, return raw r||s (matches HCE) ----
    // HCE signs raw cdol1Data (29 bytes) and returns r(32)||s(32) in tag 9F4B
    private void sendGenerateAC(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        short dataOff = apdu.getOffsetCdata();

        if (dataLen != CDOL1_DATA_LENGTH) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        atc++;

        // Sign SHA-256(cdol1Data) — ALG_ECDSA_SHA_256 does SHA-256 internally
        ecdsaSigner.init(ecPrivKey, Signature.MODE_SIGN);
        short sigLen = ecdsaSigner.sign(buf, dataOff, dataLen, sigBuf, (short)0);

        // Parse DER to raw r(32)||s(32) in scratch
        parseDER(sigBuf, (short)0, sigLen, scratch);

        // Compute SHA-256(cdol1Data) for cryptogram (first 8 bytes)
        sha256.reset();
        sha256.doFinal(buf, dataOff, dataLen, sigBuf, (short)0);
        // sigBuf[0..31] = hash, we use first 8 as cryptogram

        // Build response: 77 [ 9F27 + 9F36 + 9F26 + 9F4B ]
        short off = (short)0;
        buf[off++] = 0x77;
        short lenPos = off;
        buf[off++] = 0x00; // placeholder

        // 9F27: CID = 0x80 (ARQC)
        buf[off++] = (byte)0x9F; buf[off++] = 0x27; buf[off++] = 0x01;
        buf[off++] = (byte)0x80;

        // 9F36: ATC (2 bytes)
        buf[off++] = (byte)0x9F; buf[off++] = 0x36; buf[off++] = 0x02;
        buf[off++] = (byte)((atc >> 8) & 0xFF);
        buf[off++] = (byte)(atc & 0xFF);

        // 9F26: Application Cryptogram (8 bytes from SHA-256)
        buf[off++] = (byte)0x9F; buf[off++] = 0x26; buf[off++] = 0x08;
        Util.arrayCopyNonAtomic(sigBuf, (short)0, buf, off, (short)8);
        off += (short)8;

        // 9F4B: Signed Dynamic Application Data = r(32) || s(32)
        buf[off++] = (byte)0x9F; buf[off++] = 0x4B; buf[off++] = 0x40;
        Util.arrayCopyNonAtomic(scratch, (short)0, buf, off, (short)64);
        off += (short)64;

        // Fill length
        buf[lenPos] = (byte)(off - lenPos - 1);

        apdu.setOutgoingAndSend((short)0, off);
    }

    /**
     * Parse DER-encoded ECDSA signature to raw r(32) || s(32).
     * DER: 30 <len> 02 <rLen> <r...> 02 <sLen> <s...>
     */
    private void parseDER(byte[] der, short derOff, short derLen, byte[] out) {
        // Zero output
        Util.arrayFillNonAtomic(out, (short)0, (short)64, (byte)0);

        short pos = (short)(derOff + 2); // skip 30 <len>

        // R
        pos++; // skip 02
        short rLen = (short)(der[pos++] & 0xFF);
        short rSrc = pos;
        if (rLen == (short)33 && der[pos] == 0x00) {
            rSrc++; rLen = (short)32;
        }
        short rPad = (short)(32 - rLen);
        if (rPad < (short)0) { rPad = (short)0; rSrc = (short)(rSrc + (short)(rLen - (short)32)); rLen = (short)32; }
        Util.arrayCopyNonAtomic(der, rSrc, out, rPad, rLen);
        pos = (short)(pos + (short)(der[(short)(pos - 1)] & 0xFF));
        // Recalculate pos from original rLen
        pos = (short)(rSrc + (short)(der[(short)(rSrc - 1)] & 0xFF));
        // Actually let's just recalculate
        pos = (short)(derOff + 2); // back to start
        pos++; // skip 02
        short origRLen = (short)(der[pos++] & 0xFF);
        pos = (short)(pos + origRLen); // skip r data

        // S
        pos++; // skip 02
        short sLen = (short)(der[pos++] & 0xFF);
        short sSrc = pos;
        if (sLen == (short)33 && der[pos] == 0x00) {
            sSrc++; sLen = (short)32;
        }
        short sPad = (short)(32 - sLen);
        if (sPad < (short)0) { sPad = (short)0; sSrc = (short)(sSrc + (short)(sLen - (short)32)); sLen = (short)32; }
        Util.arrayCopyNonAtomic(der, sSrc, out, (short)(32 + sPad), sLen);
    }
}
