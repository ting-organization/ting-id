// What the app keeps on the device: secrets under their own names, and a
// saved state that is never overwritten when it cannot be read.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { asyncMem, webMem } = vi.hoisted(() => ({ asyncMem: new Map<string, string>(), webMem: new Map<string, string>() }));

vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));
vi.mock('expo-crypto', () => ({ getRandomBytes: (n: number) => new Uint8Array(n), randomUUID: () => 'id' }));
vi.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: {
    getItem: async (k: string) => asyncMem.get(k) ?? null,
    setItem: async (k: string, v: string) => void asyncMem.set(k, v),
    removeItem: async (k: string) => void asyncMem.delete(k),
    getAllKeys: async () => [...asyncMem.keys()],
    multiRemove: async (ks: string[]) => ks.forEach((k) => asyncMem.delete(k)),
  },
}));
(globalThis as { localStorage?: unknown }).localStorage = {
  getItem: (k: string) => webMem.get(k) ?? null,
  setItem: (k: string, v: string) => void webMem.set(k, v),
  removeItem: (k: string) => void webMem.delete(k),
};

beforeEach(() => {
  asyncMem.clear();
  webMem.clear();
  vi.resetModules();
});

/** A fresh store module, read from the device as at a start, once it has loaded or failed. */
async function startApp() {
  const store = await import('../src/state/store');
  await vi.waitFor(() => {
    const s = store.useStore.getState();
    if (!s.hydrated && !s.loadError) throw new Error('still loading');
  });
  return store;
}

const saved = (state: object, version = 8) => JSON.stringify({ state, version });

describe('secrets', () => {
  it('are saved, read and deleted under the ting.key. prefix', async () => {
    const { deleteSecret, loadSecret, saveSecret } = await import('../src/state/keystore');
    await saveSecret('k1', 'aa11');
    expect(webMem.get('ting.key.k1')).toBe('aa11');
    expect(await loadSecret('k1')).toBe('aa11');
    await deleteSecret('k1');
    expect(webMem.has('ting.key.k1')).toBe(false);
    expect(await loadSecret('k1')).toBeNull();
  });
});

describe('the saved state', () => {
  it('loads, and is written again on a change', async () => {
    asyncMem.set('ting-id', saved({ users: [{ id: 'u1', name: 'Anna', kind: 'key', color: '#4F46E5' }], activeUserId: 'u1' }));
    const { useStore } = await startApp();
    expect(useStore.getState().loadError).toBeNull();
    expect(useStore.getState().users.map((u) => u.name)).toEqual(['Anna']);
    useStore.getState().addContact({ id: 'c1', name: 'Kari', account: 'ab'.repeat(32) });
    await vi.waitFor(() => expect(JSON.parse(asyncMem.get('ting-id')!).state.contacts).toHaveLength(1));
  });

  it('that cannot be read is reported and never overwritten', async () => {
    asyncMem.set('ting-id', '{"state": {"users": [');
    const { useStore } = await startApp();
    expect(useStore.getState().loadError).toBeTruthy();
    expect(useStore.getState().hydrated).toBe(false);
    useStore.getState().addContact({ id: 'c1', name: 'Kari', account: 'ab'.repeat(32) });
    await new Promise((r) => setTimeout(r, 10));
    expect(asyncMem.get('ting-id')).toBe('{"state": {"users": [');
  });

  it('from format 7 is converted: older contacts and submissions get the last chain, nonce notes a time', async () => {
    asyncMem.set('ting-id-cache-chain', 'cc'.repeat(32));
    asyncMem.set(
      'ting-id',
      saved(
        {
          users: [{ id: 'u1', name: 'Anna', kind: 'key', color: '#4F46E5' }],
          contacts: [
            { id: 'c1', name: 'Kari', account: 'ab'.repeat(32) },
            { id: 'c2', name: 'Ola', account: 'cd'.repeat(32), chain: 'dd'.repeat(32) },
          ],
          submitted: [{ id: 't1', at: 1, summary: 'Send', userId: 'u1' }],
          nonces: { 'a:b': { nonce: '3', txId: 'ee'.repeat(32) } },
        },
        7,
      ),
    );
    const { useStore } = await startApp();
    const s = useStore.getState();
    expect(s.loadError).toBeNull();
    expect(s.contacts.map((c) => c.chain)).toEqual(['cc'.repeat(32), 'dd'.repeat(32)]);
    expect(s.submitted[0].chain).toBe('cc'.repeat(32));
    expect(s.nonces['a:b'].at).toBeGreaterThan(0);
    expect(s.strayPhrases).toEqual([]);
  });

  it('that fails to save is told until a save succeeds', async () => {
    asyncMem.set('ting-id', saved({ users: [] }));
    const { useStore } = await startApp();
    const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
    const setItem = AsyncStorage.setItem;
    AsyncStorage.setItem = async () => {
      throw new Error('database or disk is full');
    };
    useStore.getState().addContact({ id: 'c1', name: 'Kari', account: 'ab'.repeat(32) });
    await vi.waitFor(() => expect(useStore.getState().saveError).toBe('database or disk is full'));
    AsyncStorage.setItem = setItem;
    useStore.getState().addContact({ id: 'c2', name: 'Ola', account: 'cd'.repeat(32) });
    await vi.waitFor(() => expect(useStore.getState().saveError).toBeNull());
    expect(JSON.parse(asyncMem.get('ting-id')!).state.contacts).toHaveLength(2);
  });

  it('from another version is reported, not converted', async () => {
    const other = saved({ users: [{ id: 'u1', name: 'Anna', kind: 'key', color: '#4F46E5' }] }, 9);
    asyncMem.set('ting-id', other);
    const { useStore } = await startApp();
    expect(useStore.getState().loadError).toContain('another version');
    expect(asyncMem.get('ting-id')).toBe(other);
  });

  it('starts fresh only when asked, and removes only what Ting ID saved', async () => {
    asyncMem.set('ting-id', 'not json');
    asyncMem.set('ting-id-cache:aa', '{}');
    asyncMem.set('other-app', 'kept');
    const { startFresh, useStore } = await startApp();
    expect(useStore.getState().loadError).toBeTruthy();
    await startFresh();
    expect(useStore.getState().loadError).toBeNull();
    expect(useStore.getState().hydrated).toBe(true);
    expect(useStore.getState().users).toEqual([]);
    expect(asyncMem.has('ting-id-cache:aa')).toBe(false);
    expect(asyncMem.get('other-app')).toBe('kept');
    await vi.waitFor(() => expect(JSON.parse(asyncMem.get('ting-id')!).version).toBe(8));
  });

  it('keeps the keys and phrases an older version listed, so no secret is left unlisted', async () => {
    const key = { id: 'k1', name: 'Phone', scheme: 'secp256k1', pub: '04'.repeat(65), createdAt: 1 };
    asyncMem.set('ting-id', saved({ users: [{ id: 'u1', name: 'Anna', phrase: 'device' }, { id: 'u2', name: 'Bo' }], keys: [key, { id: 'k2', name: 'broken' }] }, 3));
    const { startFresh, useStore } = await startApp();
    expect(useStore.getState().loadError).toContain('another version');
    await startFresh();
    expect(useStore.getState().keys).toEqual([key]);
    expect(useStore.getState().users).toEqual([]);
    expect(useStore.getState().strayPhrases).toEqual([{ id: 'u1', name: 'Anna', phrase: 'device' }]);
  });
});
