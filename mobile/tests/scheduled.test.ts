// Reading an account's scheduled actions: every pending one is found, however
// many later ones were scheduled after it.
import { describe, expect, it, vi } from 'vitest';
import { Writer, hex } from '../src/chain/bytes';
import * as coin from '../src/chain/coin';
import type { ChainApi, StorageRead } from '../src/chain/node';
import { fetchScheduledMany } from '../src/state/chain';

vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));
vi.mock('expo-crypto', () => ({ getRandomBytes: (n: number) => new Uint8Array(n), randomUUID: () => 'id' }));
vi.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: { getItem: async () => null, setItem: async () => {}, removeItem: async () => {}, getAllKeys: async () => [], multiRemove: async () => {} },
}));

const coinId = new Uint8Array(32).fill(0xc0);
const account = new Uint8Array(32).fill(0xa1);
const thief = new Uint8Array(32).fill(0x7f);

/** A scheduled transfer as the coin stores it, or a minter change (op 4) that reserves only its tip. */
const transfer = (reserved: bigint) => new Writer().u64(2_000_000_000n).u128(reserved).u128(0n).u8(1).put(thief).u128(reserved).finish();
const minterChange = () => new Writer().u64(2_000_000_000n).u128(0n).u128(0n).u8(4).put(thief).finish();

/** A node holding `records` (sequence → bytes) for the account, counting what it is asked. */
function node(records: Map<bigint, Uint8Array>) {
  const keys = new Map([...records].map(([seq, bytes]) => [hex(coin.scheduledKey(account, seq)), bytes]));
  const asked = { reads: 0, requests: 0 };
  const api = {
    url: 'test',
    storageMany: async (reads: StorageRead[]) => {
      asked.requests++;
      asked.reads += reads.length;
      return reads.map((r) => keys.get(hex(r.key)) ?? null);
    },
  } as unknown as ChainApi;
  return { api, asked };
}

describe('scheduled actions', () => {
  it('are all found, even behind more than a first look of later ones', async () => {
    // The real withdrawal at 10, then 41 small ones: "cancel all" must see the first.
    const records = new Map<bigint, Uint8Array>([[10n, transfer(1_000n)]]);
    for (let seq = 50n; seq < 91n; seq++) records.set(seq, transfer(1n));
    const { api } = node(records);
    const [found] = await fetchScheduledMany(api, [{ coinId, account, nonce: 100n, locked: 1_041n }]);
    expect(found.map((s) => s.sequence)).toContain(10n);
    expect(found).toHaveLength(42);
  });

  it('cost nothing to read for an account with nothing locked', async () => {
    const { api, asked } = node(new Map());
    const [found] = await fetchScheduledMany(api, [{ coinId, account, nonce: 5_000n, locked: 0n }]);
    expect(found).toEqual([]);
    expect(asked.requests).toBe(0);
  });

  it('stop once what they reserve adds up to the locked amount', async () => {
    const { api, asked } = node(new Map([[3_998n, transfer(5n)]]));
    const [found] = await fetchScheduledMany(api, [{ coinId, account, nonce: 4_000n, locked: 5n }]);
    expect(found.map((s) => s.sequence)).toEqual([3_998n]);
    expect(asked.reads).toBe(40);
  });

  it('are read back to the first for a minter, whose minter changes may reserve nothing', async () => {
    const { api } = node(new Map([[2n, minterChange()]]));
    const [found] = await fetchScheduledMany(api, [{ coinId, account, nonce: 3_000n, locked: 0n, everything: true }]);
    expect(found.map((s) => s.sequence)).toEqual([2n]);
  });
});
