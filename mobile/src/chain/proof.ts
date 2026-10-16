// Checking a node's answers against what the chain commits to: a storage value
// against the state root by its Merkle proof (wiki: data/state-tree), and a
// block header against the block's id (wiki: data/blocks). A node can then only
// answer what the block it names holds; which block is the chain's is for the
// other nodes to confirm (state/pool).
import { equal, hex, Writer } from './bytes';
import { taggedHash } from './hash';
import { HEADER_BYTES, StorageProof } from './node';

/** The state record of storage key `key` of contract `contract` (namespace 2, wiki: data/records). */
export function recordKey(contract: Uint8Array, key: Uint8Array): Uint8Array {
  return taggedHash('state-key', Uint8Array.of(2), contract, key);
}

const EMPTY = taggedHash('state-empty');

function leaf(key: Uint8Array, value: Uint8Array): Uint8Array {
  return taggedHash('state-leaf', key, new Writer().bytes(value).finish());
}

function node(depth: number, left: Uint8Array, right: Uint8Array): Uint8Array {
  return taggedHash('state-node', new Writer().u16(depth).finish(), left, right);
}

/** Bit `depth` of a key, from the most significant bit of its first byte. */
function bit(key: Uint8Array, depth: number): number {
  return (key[depth >> 3] >> (7 - (depth & 7))) & 1;
}

/**
 * Whether `proof` shows record `key` to hold `value`, or with null to be
 * absent, in the state whose root is `root`. A proof that ends at another
 * record's leaf takes that record's value from `others` (record key, hex) and
 * the leaf is computed here, so a node cannot pass one record's leaf off as
 * another's.
 */
export function verifyRecord(root: Uint8Array, key: Uint8Array, value: Uint8Array | null, proof: StorageProof, others: Map<string, Uint8Array>): boolean {
  const depth = proof.siblings.length;
  if (depth > 256 || proof.siblings.some((s) => s.length !== 32)) return false;
  let hash: Uint8Array;
  if (proof.end === 'present' && value) hash = leaf(key, value);
  else if (proof.end === 'empty' && !value) hash = EMPTY;
  else if (proof.end === 'other' && !value && proof.otherKey?.length === 32) {
    // Another record's leaf ends the path: its key must differ and share the path so far.
    const other = proof.otherKey;
    const otherValue = others.get(hex(other));
    if (!otherValue || equal(other, key)) return false;
    for (let d = 0; d < depth; d++) if (bit(other, d) !== bit(key, d)) return false;
    hash = leaf(other, otherValue);
  } else return false;
  proof.siblings.forEach((sibling, i) => {
    const d = depth - 1 - i;
    hash = bit(key, d) ? node(d, sibling, hash) : node(d, hash, sibling);
  });
  return equal(hash, root);
}

/** What the app reads from a block header. */
export interface Header {
  chainId: Uint8Array;
  height: number;
  stateRoot: Uint8Array;
}

/** The header `bytes` if they are the header of block `id` (hex), else null. */
export function checkHeader(id: string, bytes: Uint8Array): Header | null {
  if (bytes.length !== HEADER_BYTES) return null;
  if (hex(taggedHash('block', bytes)) !== id.toLowerCase()) return null;
  // protocol_version u16, chain_id, height u64, parent, timestamp u64, target, transactions_root, receipts_root, state_root, …
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { chainId: bytes.slice(2, 34), height: Number(view.getBigUint64(34, true)), stateRoot: bytes.slice(178, 210) };
}
