/**
 * P-256 (secp256r1) key generation and ECDSA signing using @noble/curves v2.
 */

import { p256 } from "@noble/curves/nist.js";
import { sha256 } from "@noble/hashes/sha2.js";

export interface P256KeyPair {
  privateKey: Uint8Array;
  publicKeyX: Uint8Array; // 32 bytes
  publicKeyY: Uint8Array; // 32 bytes
  publicKeyUncompressed: Uint8Array; // 65 bytes (04 || X || Y)
}

export interface P256Signature {
  r: Uint8Array; // 32 bytes
  s: Uint8Array; // 32 bytes
}

/**
 * Generate a new P-256 keypair.
 */
export function generateKeyPair(): P256KeyPair {
  const privateKey = p256.utils.randomSecretKey();
  return keyPairFromPrivate(privateKey);
}

/**
 * Derive a key pair from a private key.
 */
export function keyPairFromPrivate(privateKey: Uint8Array): P256KeyPair {
  const publicKeyUncompressed = p256.getPublicKey(privateKey, false); // uncompressed
  const publicKeyX = publicKeyUncompressed.slice(1, 33);
  const publicKeyY = publicKeyUncompressed.slice(33, 65);

  return { privateKey, publicKeyX, publicKeyY, publicKeyUncompressed };
}

/**
 * Sign SHA-256(data) with the P-256 private key.
 * Returns (r, s) as 32-byte big-endian arrays.
 */
export function signData(data: Uint8Array, privateKey: Uint8Array): P256Signature {
  const hash = sha256(data);
  return signHash(hash, privateKey);
}

/**
 * Sign a pre-computed hash with the P-256 private key.
 * In @noble/curves v2, p256.sign returns a 64-byte Uint8Array (r(32) || s(32)).
 */
export function signHash(hash: Uint8Array, privateKey: Uint8Array): P256Signature {
  const sig = p256.sign(hash, privateKey, { lowS: true });
  // v2 returns Uint8Array(64): r[32] || s[32]
  const sigBytes = new Uint8Array(sig);
  const r = sigBytes.slice(0, 32);
  const s = sigBytes.slice(32, 64);
  return { r, s };
}

/**
 * Verify a P-256 signature.
 * In @noble/curves v2, p256.verify takes raw 64-byte signature.
 */
export function verify(
  hash: Uint8Array,
  signature: P256Signature,
  publicKeyX: Uint8Array,
  publicKeyY: Uint8Array
): boolean {
  const publicKey = new Uint8Array(65);
  publicKey[0] = 0x04;
  publicKey.set(publicKeyX, 1);
  publicKey.set(publicKeyY, 33);

  // Reconstruct 64-byte compact signature
  const sigBytes = new Uint8Array(64);
  sigBytes.set(signature.r, 0);
  sigBytes.set(signature.s, 32);

  return p256.verify(sigBytes, hash, publicKey, { lowS: true });
}

/**
 * Compute SHA-256 hash.
 */
export function sha256Hash(data: Uint8Array): Uint8Array {
  return sha256(data);
}
