// Ting's domain-separated hashing (wiki: data/encoding#domain-separated-hashing):
// H(tag, x) = SHA256("Ting/" || u16le(len(tag)) || tag || x).
import { sha256 } from '@noble/hashes/sha2.js';
import { concat, utf8 } from './bytes';

const PREFIX = utf8('Ting/');

export function taggedHash(tag: string, ...parts: Uint8Array[]): Uint8Array {
  const t = utf8(tag);
  const len = Uint8Array.of(t.length & 255, t.length >> 8);
  return sha256(concat(PREFIX, len, t, ...parts));
}

export { sha256 };
