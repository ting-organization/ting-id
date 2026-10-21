// Checking a node's answers: storage values against the state root by their
// Merkle proofs, and a header against its block id, on answers a node gave.
import { describe, expect, it } from 'vitest';
import { hex, unhex } from '../src/chain/bytes';
import type { StorageProof } from '../src/chain/node';
import { taggedHash } from '../src/chain/hash';
import { checkHeader, recordKey, verifyRecord } from '../src/chain/proof';
import fixture from './proof-fixture.json';

const root = unhex(fixture.state_root);
const others = new Map(Object.entries(fixture.others as Record<string, string>).map(([k, v]) => [k, unhex(v)]));
const items = fixture.values.map((v) => ({
  key: recordKey(unhex(v.contract), unhex(v.key)),
  value: typeof v.value_hex === 'string' ? unhex(v.value_hex) : null,
  proof: {
    end: v.proof.end as StorageProof['end'],
    otherKey: 'other_key' in v.proof ? unhex((v.proof as { other_key: string }).other_key) : null,
    siblings: v.proof.siblings.map((s: string) => unhex(s)),
  },
}));
const [present, absent] = items;

describe('storage proofs', () => {
  it('show present and absent records against the state root', () => {
    expect(items.map((i) => i.proof.end)).toEqual(['present', 'other', 'empty', 'empty']);
    for (const i of items) expect(verifyRecord(root, i.key, i.value, i.proof, others)).toBe(true);
    // The record key is the node's: the app derives the same one.
    expect(hex(present.key)).toBe(fixture.values[0].proof.record_key);
  });

  it('refuse another value, another root, another record or a cut path', () => {
    const changed = present.value!.slice();
    changed[0] ^= 1;
    expect(verifyRecord(root, present.key, changed, present.proof, others)).toBe(false);
    expect(verifyRecord(root, present.key, null, present.proof, others)).toBe(false);
    expect(verifyRecord(root, absent.key, Uint8Array.of(1), absent.proof, others)).toBe(false);
    const otherRoot = root.slice();
    otherRoot[31] ^= 1;
    expect(verifyRecord(otherRoot, present.key, present.value, present.proof, others)).toBe(false);
    expect(verifyRecord(root, absent.key, present.value, present.proof, others)).toBe(false);
    expect(verifyRecord(root, present.key, present.value, { ...present.proof, siblings: present.proof.siblings.slice(1) }, others)).toBe(false);
  });

  it('refuse an absence whose other record is not as listed', () => {
    const otherHex = hex(absent.proof.otherKey!);
    // Its value changed, or missing from the answer.
    const changed = new Map(others);
    changed.set(otherHex, Uint8Array.of(...others.get(otherHex)!, 0));
    expect(verifyRecord(root, absent.key, null, absent.proof, changed)).toBe(false);
    const missing = new Map(others);
    missing.delete(otherHex);
    expect(verifyRecord(root, absent.key, null, absent.proof, missing)).toBe(false);
    // The key itself named as the other record.
    expect(verifyRecord(root, absent.key, null, { ...absent.proof, otherKey: absent.key }, new Map([[hex(absent.key), Uint8Array.of(1)]]))).toBe(false);
  });

  it('refuse a present record passed off as absent behind a neighbour', () => {
    // The forgery the old proof form allowed: the record's own path and a neighbour's key.
    const neighbour = present.key.slice();
    neighbour[31] ^= 1;
    const forged = { end: 'other' as const, otherKey: neighbour, siblings: present.proof.siblings };
    const listed = new Map([[hex(neighbour), present.value!]]);
    expect(verifyRecord(root, present.key, null, forged, listed)).toBe(false);
  });
});

describe('headers', () => {
  it('are the block’s when they hash to its id, and give its state root', () => {
    const header = checkHeader(fixture.tip, unhex(fixture.header));
    expect(header).not.toBeNull();
    expect(hex(header!.stateRoot)).toBe(fixture.state_root);
    expect(header!.height).toBe(fixture.height);
    expect(hex(header!.chainId)).toBe(fixture.chain_id);
  });

  it('are refused when changed in any byte or cut short', () => {
    const bytes = unhex(fixture.header);
    const changed = bytes.slice();
    changed[200] ^= 1;
    expect(checkHeader(fixture.tip, changed)).toBeNull();
    expect(checkHeader(fixture.tip, bytes.slice(0, 293))).toBeNull();
    expect(checkHeader('00'.repeat(32), bytes)).toBeNull();
  });

  it('need the proof of work their target asks, within the chain’s limit', () => {
    const bytes = unhex(fixture.header);
    // The production network's limit, 2^228 - 1, and the block's own target.
    const limit = '0000000' + 'f'.repeat(57);
    expect(checkHeader(fixture.tip, bytes, limit)).not.toBeNull();
    expect(hex(checkHeader(fixture.tip, bytes)!.target) <= limit).toBe(true);
    expect(checkHeader(fixture.tip, bytes, '00000000' + 'f'.repeat(56))).toBeNull();
    // A header whose id is above its own target has no work: so is one with target zero.
    for (const fill of [0x00, 0x01]) {
      const easy = bytes.slice();
      easy.fill(0, 82, 114);
      easy[113] = fill;
      expect(checkHeader(hex(taggedHash('block', easy)), easy)).toBeNull();
    }
  });
});
