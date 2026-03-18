/**
 * EMV Card Simulator
 *
 * Simulates the HCE (Host Card Emulation) app behavior.
 * Responds to APDU commands as a contactless EMV card.
 */

import { type P256KeyPair, type P256Signature, signData, sha256Hash } from "./p256.js";
import {
  encodeTLV,
  encodeTLVList,
  wrapTLV,
  hexToBytes,
  bytesToHex,
} from "./tlv.js";

const SW_OK = new Uint8Array([0x90, 0x00]);
const SW_WRONG_LENGTH = new Uint8Array([0x67, 0x00]);
const SW_UNKNOWN = new Uint8Array([0x6d, 0x00]);

export const AID = hexToBytes("A0000009510001");

export interface CardConfig {
  keyPair: P256KeyPair;
  pan: string; // 16-digit PAN as hex string (e.g., "9510010000000001")
  expiryDate: string; // YYMMDD BCD (e.g., "261231")
  issuerCountryCode: string; // 2-byte hex (e.g., "0036")
}

export class CardSimulator {
  private config: CardConfig;
  private atc: number = 0; // Application Transaction Counter
  private lastCdol1Data: Uint8Array | null = null;
  private lastSignature: P256Signature | null = null;

  constructor(config: CardConfig) {
    this.config = config;
  }

  /**
   * Process an APDU command and return the response.
   */
  processAPDU(apdu: Uint8Array): Uint8Array {
    if (apdu.length < 4) return SW_UNKNOWN;

    const cla = apdu[0];
    const ins = apdu[1];
    const p1 = apdu[2];
    const p2 = apdu[3];

    // SELECT (by AID)
    if (cla === 0x00 && ins === 0xa4 && p1 === 0x04 && p2 === 0x00) {
      return this.handleSelect(apdu);
    }

    // GET PROCESSING OPTIONS
    if (cla === 0x80 && ins === 0xa8 && p1 === 0x00 && p2 === 0x00) {
      return this.handleGPO(apdu);
    }

    // READ RECORD
    if (cla === 0x00 && ins === 0xb2) {
      return this.handleReadRecord(apdu);
    }

    // GENERATE AC
    if (cla === 0x80 && ins === 0xae) {
      return this.handleGenerateAC(apdu);
    }

    return SW_UNKNOWN;
  }

  /**
   * Handle SELECT command.
   * Returns FCI with PDOL.
   */
  private handleSelect(apdu: Uint8Array): Uint8Array {
    const lc = apdu[4];
    const aidData = apdu.slice(5, 5 + lc);

    // Verify AID matches
    if (bytesToHex(aidData).toUpperCase() !== bytesToHex(AID).toUpperCase()) {
      return new Uint8Array([0x6a, 0x82]); // File not found
    }

    // PDOL: TTQ(4) + Amount(6) + Currency(2) + UN(4) = 16 bytes
    const pdol = hexToBytes("9F660400 9F020600 5F2A0200 9F370400".replace(/\s/g, ""));

    // Build FCI Proprietary Data (tag A5)
    const fciPropData = encodeTLVList([
      { tag: "50", value: new TextEncoder().encode("AUDS PAY") }, // Application Label
      { tag: "9F38", value: pdol }, // PDOL
    ]);

    // Build FCI Template (tag 6F)
    const fciInner = encodeTLVList([
      { tag: "84", value: AID }, // DF Name
      { tag: "A5", value: fciPropData }, // FCI Proprietary Data
    ]);

    const fci = wrapTLV("6F", fciInner);
    return concat(fci, SW_OK);
  }

  /**
   * Handle GET PROCESSING OPTIONS.
   * Returns AIP and AFL.
   */
  private handleGPO(_apdu: Uint8Array): Uint8Array {
    // AIP: 3900 (CDA supported, issuer auth supported)
    const aip = hexToBytes("3900");
    // AFL: SFI 1, record 1 to 1, 0 offline auth records
    const afl = hexToBytes("08010100");

    const responseData = encodeTLVList([
      { tag: "82", value: aip },
      { tag: "94", value: afl },
    ]);

    const response = wrapTLV("77", responseData);
    return concat(response, SW_OK);
  }

  /**
   * Handle READ RECORD.
   * Returns card data including PAN, pubkey, CDOL1.
   */
  private handleReadRecord(_apdu: Uint8Array): Uint8Array {
    const pan = hexToBytes(this.config.pan);
    const expiry = hexToBytes(this.config.expiryDate);
    const issuerCountry = hexToBytes(this.config.issuerCountryCode);

    // Build Track 2 equivalent: PAN + D + Expiry + Service Code + discretionary
    const track2 = hexToBytes(
      this.config.pan + "D" + this.config.expiryDate.slice(0, 4) + "201" + "0000000000" + "F"
    );

    // CDOL1 DOL: defines what the terminal must send in GENERATE AC
    // 9F02(6) 9F03(6) 9F1A(2) 95(5) 5F2A(2) 9A(3) 9C(1) 9F37(4) = 29 bytes
    const cdol1Dol = hexToBytes("9F02069F03069F1A0295055F2A029A039C019F3704");

    const recordData = encodeTLVList([
      { tag: "57", value: track2 },
      { tag: "5A", value: pan },
      { tag: "5F24", value: expiry },
      { tag: "5F28", value: issuerCountry },
      { tag: "8C", value: cdol1Dol },
      // P-256 public key: X in tag 9F47, Y in tag 9F48
      { tag: "9F47", value: this.config.keyPair.publicKeyX },
      { tag: "9F48", value: this.config.keyPair.publicKeyY },
    ]);

    const record = wrapTLV("70", recordData);
    return concat(record, SW_OK);
  }

  /**
   * Handle GENERATE AC.
   * Signs SHA-256(CDOL1 data) with P-256 private key.
   */
  private handleGenerateAC(apdu: Uint8Array): Uint8Array {
    const lc = apdu[4];
    const cdol1Data = apdu.slice(5, 5 + lc);

    if (cdol1Data.length !== 29) {
      return SW_WRONG_LENGTH;
    }

    // Store CDOL1 data
    this.lastCdol1Data = cdol1Data;

    // Sign SHA-256(CDOL1 data)
    const signature = signData(cdol1Data, this.config.keyPair.privateKey);
    this.lastSignature = signature;

    // Increment ATC
    this.atc++;

    // Build response
    const hash = sha256Hash(cdol1Data);
    const cryptogram = hash.slice(0, 8); // First 8 bytes for EMV compat

    // Signed Dynamic Application Data: r(32) || s(32)
    const signedData = new Uint8Array(64);
    signedData.set(signature.r, 0);
    signedData.set(signature.s, 32);

    const atcBytes = new Uint8Array(2);
    atcBytes[0] = (this.atc >> 8) & 0xff;
    atcBytes[1] = this.atc & 0xff;

    const responseData = encodeTLVList([
      { tag: "9F27", value: new Uint8Array([0x80]) }, // CID: ARQC
      { tag: "9F36", value: atcBytes }, // ATC
      { tag: "9F26", value: cryptogram }, // Application Cryptogram
      { tag: "9F4B", value: signedData }, // Signed Dynamic Application Data
    ]);

    const response = wrapTLV("77", responseData);
    return concat(response, SW_OK);
  }

  getLastCDOL1Data(): Uint8Array | null {
    return this.lastCdol1Data;
  }

  getLastSignature(): P256Signature | null {
    return this.lastSignature;
  }
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const result = new Uint8Array(a.length + b.length);
  result.set(a);
  result.set(b, a.length);
  return result;
}
