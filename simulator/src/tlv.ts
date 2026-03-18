/**
 * BER-TLV encoder/decoder for EMV data.
 */

export interface TLVEntry {
  tag: string;
  length: number;
  value: Uint8Array;
}

/**
 * Parse BER-TLV encoded data into tag-value pairs.
 */
export function parseTLV(data: Uint8Array): TLVEntry[] {
  const entries: TLVEntry[] = [];
  let offset = 0;

  while (offset < data.length) {
    // Parse tag
    let tag = data[offset].toString(16).padStart(2, "0").toUpperCase();
    offset++;

    // Multi-byte tag: if lower 5 bits of first byte are all 1s
    if ((parseInt(tag, 16) & 0x1f) === 0x1f) {
      while (offset < data.length) {
        tag += data[offset].toString(16).padStart(2, "0").toUpperCase();
        offset++;
        // Last byte of tag has bit 8 = 0
        if ((data[offset - 1] & 0x80) === 0) break;
      }
    }

    if (offset >= data.length) break;

    // Parse length
    let length = data[offset];
    offset++;

    if (length === 0x81) {
      length = data[offset];
      offset++;
    } else if (length === 0x82) {
      length = (data[offset] << 8) | data[offset + 1];
      offset += 2;
    } else if (length > 0x82) {
      throw new Error(`Unsupported TLV length encoding: ${length}`);
    }

    // Parse value
    const value = data.slice(offset, offset + length);
    offset += length;

    entries.push({ tag, length, value });
  }

  return entries;
}

/**
 * Encode a single TLV entry.
 */
export function encodeTLV(tag: string, value: Uint8Array): Uint8Array {
  const tagBytes = hexToBytes(tag);
  const lenBytes = encodeLength(value.length);
  const result = new Uint8Array(tagBytes.length + lenBytes.length + value.length);
  result.set(tagBytes);
  result.set(lenBytes, tagBytes.length);
  result.set(value, tagBytes.length + lenBytes.length);
  return result;
}

/**
 * Encode multiple TLV entries and concatenate.
 */
export function encodeTLVList(
  entries: Array<{ tag: string; value: Uint8Array }>
): Uint8Array {
  const encoded = entries.map((e) => encodeTLV(e.tag, e.value));
  const totalLen = encoded.reduce((sum, e) => sum + e.length, 0);
  const result = new Uint8Array(totalLen);
  let offset = 0;
  for (const e of encoded) {
    result.set(e, offset);
    offset += e.length;
  }
  return result;
}

/**
 * Wrap TLV data in a constructed tag (e.g., 6F, 77, 70).
 */
export function wrapTLV(tag: string, innerData: Uint8Array): Uint8Array {
  return encodeTLV(tag, innerData);
}

function encodeLength(len: number): Uint8Array {
  if (len < 0x80) {
    return new Uint8Array([len]);
  } else if (len < 0x100) {
    return new Uint8Array([0x81, len]);
  } else {
    return new Uint8Array([0x82, (len >> 8) & 0xff, len & 0xff]);
  }
}

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.replace(/\s/g, "");
  const bytes = new Uint8Array(clean.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(clean.substring(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Find a TLV entry by tag (case-insensitive).
 */
export function findTag(entries: TLVEntry[], tag: string): TLVEntry | undefined {
  return entries.find((e) => e.tag.toUpperCase() === tag.toUpperCase());
}
