// Reading an identity: its core, keys and slots come from one block, even when
// the identity changes between the reads.
import { describe, expect, it, vi } from 'vitest';
import { concat, hex } from '../src/chain/bytes';
import * as idp from '../src/chain/identity';
import type { ChainApi, StorageRead } from '../src/chain/node';
import { fetchIdentity } from '../src/state/chain';

vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));
vi.mock('expo-crypto', () => ({ getRandomBytes: (n: number) => new Uint8Array(n), randomUUID: () => 'id' }));
vi.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: { getItem: async () => null, setItem: async () => {}, removeItem: async () => {}, getAllKeys: async () => [], multiRemove: async () => {} },
}));

const registry = new Uint8Array(32).fill(0x1d);
const id = new Uint8Array(32).fill(0x2e);
const policy = new Uint8Array(32).fill(0x3f);
const core = (nextKeyId: bigint): idp.Core => ({
  opNonce: 0n,
  changeEpoch: 0n,
  recoveryEpoch: 0n,
  keyEpoch: 0n,
  nextKeyId,
  keyCount: nextKeyId,
  guardianCount: 0n,
  slotCount: 1n,
  threshold: 0n,
  changeDelay: 0n,
  recoveryDelay: 0n,
  nextChange: 0n,
});
const keyRecord = (n: number) => concat(policy, new Uint8Array(40).fill(n));

/** A registry whose identity gains a key right after the first read of its core. */
function node() {
  const records = new Map<string, Uint8Array>([
    [hex(idp.coreKey(id)), idp.encodeCore(core(1n))],
    [hex(idp.keyKey(id, 0n, 0n)), keyRecord(1)],
  ]);
  const get = (key: Uint8Array) => records.get(hex(key)) ?? null;
  return {
    url: 'test',
    storage: async (_contract: Uint8Array, key: Uint8Array) => {
      const value = get(key);
      records.set(hex(idp.coreKey(id)), idp.encodeCore(core(2n)));
      records.set(hex(idp.keyKey(id, 0n, 1n)), keyRecord(2));
      return value;
    },
    storageMany: async (reads: StorageRead[]) => reads.map((r) => get(r.key)),
  } as unknown as ChainApi;
}

describe('identity reads', () => {
  it('read again from a core that changed between the reads', async () => {
    const identity = await fetchIdentity(node(), registry, id);
    expect(identity?.core.nextKeyId).toBe(2n);
    expect(identity?.keys.map((k) => k.keyId)).toEqual([0n, 1n]);
  });
});
