// The cache of what the app read of a chain: values keep their identity when a
// read changes nothing, a late read never overwrites a newer one, and the
// device copy comes back as it was saved.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChainCache, CacheStorage, decodeValue, encodeValue } from '../src/state/cache';

const { asyncMem } = vi.hoisted(() => ({ asyncMem: new Map<string, string>() }));

vi.mock('@react-native-async-storage/async-storage', () => ({
  default: {
    getItem: async (k: string) => asyncMem.get(k) ?? null,
    setItem: async (k: string, v: string) => void asyncMem.set(k, v),
    removeItem: async (k: string) => void asyncMem.delete(k),
  },
}));

const DAY = 86_400_000;

function memory(): CacheStorage & { map: Map<string, string> } {
  const map = new Map<string, string>();
  return { map, getItem: async (k) => map.get(k) ?? null, setItem: async (k, v) => void map.set(k, v) };
}

async function loaded(storage = memory()): Promise<ChainCache> {
  const cache = new ChainCache('aa', storage);
  await cache.load();
  return cache;
}

beforeEach(() => {
  asyncMem.clear();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('values as text', () => {
  it('keep bigints, byte strings and maps', () => {
    const value = {
      amount: 10_000_000_000n,
      id: Uint8Array.of(1, 2, 255),
      empty: new Uint8Array(),
      views: new Map([['c1', [{ nonce: 3n, scheduled: [] }]]]),
      none: null,
      text: 'Spending',
    };
    expect(decodeValue(encodeValue(value))).toEqual(value);
  });

  it('leave ordinary objects that only look tagged alone', () => {
    const value = { $n: '1', other: true };
    expect(decodeValue(encodeValue(value))).toEqual(value);
  });
});

describe('a chain cache', () => {
  it('keeps the value screens have when a read changes nothing', async () => {
    const cache = await loaded();
    cache.put('coin:c1', { supply: 5n }, 't1');
    const first = cache.get('coin:c1')!.value;
    const revision = cache.revision;
    cache.put('coin:c1', { supply: 5n }, 't2');
    expect(cache.get('coin:c1')!.value).toBe(first);
    expect(cache.get('coin:c1')!.version).toBe('t2');
    expect(cache.revision).toBe(revision);
    cache.put('coin:c1', { supply: 6n }, 't3');
    expect(cache.get('coin:c1')!.value).toEqual({ supply: 6n });
    expect(cache.revision).toBe(revision + 1);
  });

  it('drops a read that a newer one overtook', async () => {
    const cache = await loaded();
    cache.begin(['view:a'], 't1');
    expect(cache.stale('view:a', 't1')).toBe(false);
    cache.begin(['view:a'], 't2');
    cache.put('view:a', 'old', 't1');
    expect(cache.get('view:a')).toBeUndefined();
    cache.put('view:a', 'new', 't2');
    expect(cache.get('view:a')!.value).toBe('new');
    expect(cache.stale('view:a', 't2')).toBe(false);
    expect(cache.stale('view:a', 't3')).toBe(true);
  });

  it('reads again after a failed read, and everything after a refresh', async () => {
    const cache = await loaded();
    cache.begin(['run:x'], 't1');
    cache.fail('run:x', 't1');
    expect(cache.stale('run:x', 't1')).toBe(true);
    cache.put('run:x', 'queued', 't1');
    const epoch = cache.epoch;
    cache.invalidate();
    expect(cache.epoch).toBe(epoch + 1);
    expect(cache.stale('run:x', 't1')).toBe(true);
    expect(cache.get('run:x')!.value).toBe('queued');
  });

  it('comes back from the device as it was saved', async () => {
    const storage = memory();
    const cache = await loaded(storage);
    cache.put('identity:r:i', { id: Uint8Array.of(7), keys: [{ keyId: 0n }] }, 't9');
    cache.put('run:y', null, 't9');
    await cache.save();
    const again = await loaded(storage);
    expect(again.get('identity:r:i')!.value).toEqual({ id: Uint8Array.of(7), keys: [{ keyId: 0n }] });
    expect(again.get('identity:r:i')!.version).toBe('t9');
    expect(again.get('run:y')!.value).toBeNull();
    // Another chain has a cache of its own.
    const other = new ChainCache('bb', storage);
    await other.load();
    expect(other.get('identity:r:i')).toBeUndefined();
  });

  it('saves nothing before its saved copy was read, and leaves out values long unused', async () => {
    const storage = memory();
    const early = new ChainCache('aa', storage);
    early.put('coin:c1', 1, 't1');
    await early.save();
    expect(storage.map.size).toBe(0);

    const cache = await loaded(storage);
    cache.put('coin:old', 1, 't1');
    vi.setSystemTime(Date.now() + 31 * DAY);
    cache.put('coin:new', 2, 't2');
    await cache.save();
    const again = await loaded(storage);
    expect(again.get('coin:old')).toBeUndefined();
    expect(again.get('coin:new')!.value).toBe(2);
  });

  it('drops a copy saved in another format', async () => {
    const storage = memory();
    storage.map.set('ting-id-cache:aa', '{"format":0,"entries":[["coin:c1","t1",0,1]]}');
    const cache = await loaded(storage);
    expect(cache.get('coin:c1')).toBeUndefined();
  });
});

describe('the current chain', () => {
  it('is the one the app last saw, until a node answers from another', async () => {
    vi.resetModules();
    const first = await import('../src/state/cache');
    const cache = first.selectChain('aa');
    await cache.load();
    cache.put('status', { height: 5 }, '');
    await cache.save();

    vi.resetModules();
    const next = await import('../src/state/cache');
    await next.openLastCache();
    expect(next.selectChain('aa').get('status')!.value).toEqual({ height: 5 });
    expect(next.selectChain('bb').get('status')).toBeUndefined();
    expect(asyncMem.get('ting-id-cache-chain')).toBe('bb');
  });
});
