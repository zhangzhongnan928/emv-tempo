/**
 * EMV Terminal Simulator
 *
 * Drives the EMV contactless APDU flow against a card simulator.
 * After the flow completes, extracts data needed for on-chain settlement.
 */

import { CardSimulator, AID } from "./card-sim.js";
import { parseTLV, findTag, hexToBytes, bytesToHex } from "./tlv.js";

export interface TerminalConfig {
  terminalId: string; // 8-byte hex terminal ID
  amount: number; // Amount in cents (e.g., 1000 = $10.00)
  currencyCode: string; // 2-byte hex (e.g., "0036" for AUD)
  countryCode: string; // 2-byte hex (e.g., "0036" for Australia)
}

export interface EMVResult {
  cdol1Data: Uint8Array; // 29-byte CDOL1 that the card signed
  pan: string; // 8-byte PAN hex
  terminalId: string; // 8-byte terminal ID hex
  sigR: Uint8Array; // 32-byte P-256 signature R
  sigS: Uint8Array; // 32-byte P-256 signature S
  pubKeyX: Uint8Array; // 32-byte public key X
  pubKeyY: Uint8Array; // 32-byte public key Y
  amount: number; // Amount in cents
}

export class TerminalSimulator {
  private config: TerminalConfig;

  constructor(config: TerminalConfig) {
    this.config = config;
  }

  /**
   * Run the full EMV contactless flow against a card.
   */
  runTransaction(card: CardSimulator): EMVResult {
    // 1. SELECT by AID
    console.log("  [Terminal] SELECT AID A000006690820001");
    const selectApdu = this.buildSelectApdu(AID);
    const selectResp = card.processAPDU(selectApdu);
    this.checkSW(selectResp, "SELECT");
    console.log("  [Terminal] SELECT OK - Got FCI");

    // 2. GET PROCESSING OPTIONS
    console.log("  [Terminal] GET PROCESSING OPTIONS");
    const un = this.generateUnpredictableNumber();
    const pdolData = this.buildPDOLData(un);
    const gpoApdu = this.buildGPOApdu(pdolData);
    const gpoResp = card.processAPDU(gpoApdu);
    this.checkSW(gpoResp, "GPO");
    console.log("  [Terminal] GPO OK - Got AIP/AFL");

    // 3. READ RECORD (SFI 1, Record 1)
    console.log("  [Terminal] READ RECORD (SFI 1, Record 1)");
    const readApdu = this.buildReadRecordApdu(1, 1);
    const readResp = card.processAPDU(readApdu);
    this.checkSW(readResp, "READ RECORD");

    // Parse card data
    const recordBody = readResp.slice(0, readResp.length - 2);
    const recordTlv = parseTLV(recordBody);
    // The outer tag is 70, parse its value
    const tag70 = findTag(recordTlv, "70");
    if (!tag70) throw new Error("Missing tag 70 in READ RECORD response");
    const innerTlv = parseTLV(tag70.value);

    const panTag = findTag(innerTlv, "5A");
    if (!panTag) throw new Error("Missing PAN (tag 5A)");
    const pan = bytesToHex(panTag.value);

    const pubKeyXTag = findTag(innerTlv, "9F47");
    const pubKeyYTag = findTag(innerTlv, "9F48");
    if (!pubKeyXTag || !pubKeyYTag)
      throw new Error("Missing public key (tags 9F47/9F48)");

    console.log(`  [Terminal] READ RECORD OK - PAN: ${pan}, Got public key`);

    // 4. GENERATE AC
    console.log("  [Terminal] GENERATE AC (ARQC)");
    const cdol1Data = this.buildCDOL1Data(un);
    const genACApdu = this.buildGenerateACApdu(cdol1Data);
    const genACResp = card.processAPDU(genACApdu);
    this.checkSW(genACResp, "GENERATE AC");

    // Parse signature from response
    const acBody = genACResp.slice(0, genACResp.length - 2);
    const acTlv = parseTLV(acBody);
    const tag77 = findTag(acTlv, "77");
    if (!tag77) throw new Error("Missing tag 77 in GENERATE AC response");
    const acInnerTlv = parseTLV(tag77.value);

    const sigTag = findTag(acInnerTlv, "9F4B");
    if (!sigTag || sigTag.value.length !== 64)
      throw new Error("Missing or invalid signature (tag 9F4B)");

    const sigR = sigTag.value.slice(0, 32);
    const sigS = sigTag.value.slice(32, 64);

    console.log(`  [Terminal] GENERATE AC OK - Got P-256 signature`);

    // Pad PAN to 8 bytes (left-padded with zeros if needed)
    const panPadded = pan.padStart(16, "0").slice(0, 16);

    return {
      cdol1Data,
      pan: panPadded,
      terminalId: this.config.terminalId,
      sigR,
      sigS,
      pubKeyX: pubKeyXTag.value,
      pubKeyY: pubKeyYTag.value,
      amount: this.config.amount,
    };
  }

  // --- APDU Builders ---

  private buildSelectApdu(aid: Uint8Array): Uint8Array {
    // 00 A4 04 00 [Lc] [AID] 00
    const apdu = new Uint8Array(6 + aid.length);
    apdu[0] = 0x00; // CLA
    apdu[1] = 0xa4; // INS
    apdu[2] = 0x04; // P1
    apdu[3] = 0x00; // P2
    apdu[4] = aid.length; // Lc
    apdu.set(aid, 5);
    apdu[5 + aid.length] = 0x00; // Le
    return apdu;
  }

  private buildGPOApdu(pdolData: Uint8Array): Uint8Array {
    // 80 A8 00 00 [Lc] 83 [len] [PDOL data] 00
    const dataLen = 2 + pdolData.length; // tag 83 + length byte + data
    const apdu = new Uint8Array(5 + dataLen + 1);
    apdu[0] = 0x80; // CLA
    apdu[1] = 0xa8; // INS
    apdu[2] = 0x00; // P1
    apdu[3] = 0x00; // P2
    apdu[4] = dataLen; // Lc
    apdu[5] = 0x83; // Command Template tag
    apdu[6] = pdolData.length;
    apdu.set(pdolData, 7);
    apdu[7 + pdolData.length] = 0x00; // Le
    return apdu;
  }

  private buildReadRecordApdu(record: number, sfi: number): Uint8Array {
    // 00 B2 [record#] [SFI<<3 | 0x04] 00
    return new Uint8Array([0x00, 0xb2, record, (sfi << 3) | 0x04, 0x00]);
  }

  private buildGenerateACApdu(cdol1Data: Uint8Array): Uint8Array {
    // 80 AE 80 00 [Lc] [CDOL1 data] 00
    const apdu = new Uint8Array(6 + cdol1Data.length);
    apdu[0] = 0x80; // CLA
    apdu[1] = 0xae; // INS
    apdu[2] = 0x80; // P1: ARQC
    apdu[3] = 0x00; // P2
    apdu[4] = cdol1Data.length; // Lc
    apdu.set(cdol1Data, 5);
    apdu[5 + cdol1Data.length] = 0x00; // Le
    return apdu;
  }

  // --- Data Builders ---

  /**
   * Build PDOL data: TTQ(4) + Amount(6) + Currency(2) + UN(4) = 16 bytes
   */
  private buildPDOLData(un: Uint8Array): Uint8Array {
    const data = new Uint8Array(16);
    // TTQ: 4 bytes (basic contactless transaction)
    data[0] = 0x36; // qVSDC supported, MSD supported
    data[1] = 0x00;
    data[2] = 0x00;
    data[3] = 0x00;
    // Amount: 6 bytes BCD
    const amountBcd = this.amountToBCD(this.config.amount);
    data.set(amountBcd, 4);
    // Currency: 2 bytes
    const currency = hexToBytes(this.config.currencyCode);
    data.set(currency, 10);
    // UN: 4 bytes
    data.set(un, 12);
    return data;
  }

  /**
   * Build the 29-byte CDOL1 data.
   */
  private buildCDOL1Data(un: Uint8Array): Uint8Array {
    const data = new Uint8Array(29);

    // 9F02: Amount Authorized (6 bytes BCD)
    const amountBcd = this.amountToBCD(this.config.amount);
    data.set(amountBcd, 0);

    // 9F03: Amount Other (6 bytes) - zeros
    // data[6..11] already 0

    // 9F1A: Terminal Country Code (2 bytes)
    const country = hexToBytes(this.config.countryCode);
    data.set(country, 12);

    // 95: TVR (5 bytes) - zeros
    // data[14..18] already 0

    // 5F2A: Transaction Currency Code (2 bytes)
    const currency = hexToBytes(this.config.currencyCode);
    data.set(currency, 19);

    // 9A: Transaction Date (3 bytes YYMMDD BCD)
    const now = new Date();
    const yy = now.getFullYear() % 100;
    const mm = now.getMonth() + 1;
    const dd = now.getDate();
    data[21] = ((Math.floor(yy / 10) << 4) | (yy % 10));
    data[22] = ((Math.floor(mm / 10) << 4) | (mm % 10));
    data[23] = ((Math.floor(dd / 10) << 4) | (dd % 10));

    // 9C: Transaction Type (1 byte) - purchase
    data[24] = 0x00;

    // 9F37: Unpredictable Number (4 bytes)
    data.set(un, 25);

    return data;
  }

  /**
   * Convert amount in cents to 6-byte BCD.
   * e.g., 1000 → 0x00 0x00 0x00 0x00 0x10 0x00
   */
  private amountToBCD(cents: number): Uint8Array {
    const bcd = new Uint8Array(6);
    let val = cents;
    for (let i = 5; i >= 0; i--) {
      const lo = val % 10;
      val = Math.floor(val / 10);
      const hi = val % 10;
      val = Math.floor(val / 10);
      bcd[i] = (hi << 4) | lo;
    }
    return bcd;
  }

  private generateUnpredictableNumber(): Uint8Array {
    const un = new Uint8Array(4);
    crypto.getRandomValues(un);
    return un;
  }

  private checkSW(response: Uint8Array, command: string): void {
    if (response.length < 2) throw new Error(`${command}: Empty response`);
    const sw1 = response[response.length - 2];
    const sw2 = response[response.length - 1];
    if (sw1 !== 0x90 || sw2 !== 0x00) {
      throw new Error(
        `${command}: Failed with SW ${sw1.toString(16)}${sw2.toString(16)}`
      );
    }
  }
}
