// Device state: settings, keys, users, contacts and submitted transactions,
// persisted with AsyncStorage. Key secrets are in the keystore, never here.
import { useMemo } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { hex, isHex } from '@/chain/bytes';
import { SCHEMES } from '@/chain/keys';
import { setSubmitListener } from '@/chain/node';
import { nodeAddress } from '@/chain/pool';
import { currentChain, forgetCaches, openLastCache, useChainCache } from './cache';
import type { Contact, KeyMeta, OutboxEntry, Settings, SignatureNote, SubmittedTx, User } from './types';

/** A node on this device. */
export const DEFAULT_NODE_URL = 'http://127.0.0.1:18441';

/**
 * The nodes a new installation starts with: the ones a release build was made
 * with (EXPO_PUBLIC_TING_NODES, comma-separated), else the node on this device.
 */
export function defaultNodes(): string[] {
  const named = String(process.env.EXPO_PUBLIC_TING_NODES ?? '')
    .split(',')
    .map((u) => nodeAddress(u))
    .filter((u): u is string => u !== null);
  return named.length > 0 ? [...new Set(named)] : [DEFAULT_NODE_URL];
}

export const USER_COLORS = ['#4F46E5', '#0EA5A4', '#D97706', '#DB2777', '#059669', '#7C3AED', '#2563EB', '#DC2626'];

/** The fee each operation pays unless the user sets another, in the fee coin. */
export const DEFAULT_FEE = '0.0001';

/**
 * The last action this device signed for a coin account: its nonce and
 * transaction, and for a fee payment bound to a batch the prefix it commits to.
 */
export interface NonceNote {
  nonce: string;
  txId: string;
  prefix?: string;
}

interface State {
  hydrated: boolean;
  /** Why the saved state could not be read; the app shows only this until the person starts fresh. */
  loadError: string | null;
  settings: Settings;
  keys: KeyMeta[];
  users: User[];
  contacts: Contact[];
  submitted: SubmittedTx[];
  activeUserId: string | null;
  activeSlot: number;
  /** The coin the send screen last used; a convenience, not a preference. */
  activeCoin: string | null;
  /**
   * The last action signed per coin account (`coin:account`), so that the
   * next one takes the following nonce while it still waits for a block.
   */
  nonces: Record<string, NonceNote>;
  /** Signing requests of sites this device answered, newest first. */
  signatures: SignatureNote[];
  /** The bytes of submitted transactions no block has included yet, by id. */
  outbox: Record<string, OutboxEntry>;

  setSettings(patch: Partial<Settings>): void;
  addCoin(id: string): void;
  removeCoin(id: string): void;
  dismissCoin(id: string): void;
  setActiveCoin(id: string | null): void;
  addKey(key: KeyMeta): void;
  renameKey(id: string, name: string): void;
  removeKey(id: string): void;
  addUser(user: User): void;
  updateUser(id: string, patch: Partial<User>): void;
  removeUser(id: string): void;
  setActiveUser(id: string | null): void;
  setActiveSlot(slot: number): void;
  addContact(contact: Contact): void;
  removeContact(id: string): void;
  addSubmitted(tx: SubmittedTx): void;
  noteNonce(key: string, note: NonceNote): void;
  addSignature(note: SignatureNote): void;
  clearSignatures(): void;
  updateSubmitted(id: string, patch: Partial<SubmittedTx>): void;
  keepOutbox(id: string, entry: OutboxEntry): void;
  /** Notes that `unpaid` is the operation of the batch `paid` sent alone. */
  pairOutbox(paid: string, unpaid: string): void;
  forgetOutbox(id: string): void;
}

const defaultSettings = (): Settings => ({ nodeUrls: defaultNodes(), coins: [], dismissed: [], advanced: false, feeCoin: null, fee: DEFAULT_FEE, theme: 'system' });

const STORE_NAME = 'ting-id';

/**
 * Nothing is written before the saved state was read: a state that could not
 * be read is never overwritten, unless the person chooses to start fresh.
 */
let writable = false;

const storage = {
  getItem: (name: string) => AsyncStorage.getItem(name),
  setItem: (name: string, value: string) => (writable ? AsyncStorage.setItem(name, value) : Promise.resolve()),
  removeItem: (name: string) => (writable ? AsyncStorage.removeItem(name) : Promise.resolve()),
};

/** What a new installation starts with. */
const emptyData = () => ({
  settings: defaultSettings(),
  keys: [] as KeyMeta[],
  users: [] as User[],
  contacts: [] as Contact[],
  submitted: [] as SubmittedTx[],
  activeUserId: null as string | null,
  activeSlot: 0,
  activeCoin: null as string | null,
  nonces: {} as Record<string, NonceNote>,
  signatures: [] as SignatureNote[],
  outbox: {} as Record<string, OutboxEntry>,
});

export const useStore = create<State>()(
  persist(
    (set) => ({
      hydrated: false,
      loadError: null,
      ...emptyData(),

      setSettings: (patch) => set((s) => ({ settings: { ...s.settings, ...patch } })),
      addCoin: (id) =>
        set((s) => ({
          settings: {
            ...s.settings,
            coins: s.settings.coins.includes(id) ? s.settings.coins : [...s.settings.coins, id],
            dismissed: s.settings.dismissed.filter((d) => d !== id),
          },
        })),
      removeCoin: (id) =>
        set((s) => ({
          settings: { ...s.settings, coins: s.settings.coins.filter((c) => c !== id) },
          activeCoin: s.activeCoin === id ? null : s.activeCoin,
        })),
      dismissCoin: (id) => set((s) => ({ settings: { ...s.settings, dismissed: [...new Set([...s.settings.dismissed, id])] } })),
      setActiveCoin: (id) => set({ activeCoin: id }),
      addKey: (key) => set((s) => ({ keys: [...s.keys, key] })),
      renameKey: (id, name) => set((s) => ({ keys: s.keys.map((k) => (k.id === id ? { ...k, name } : k)) })),
      removeKey: (id) => set((s) => ({ keys: s.keys.filter((k) => k.id !== id) })),
      addUser: (user) =>
        set((s) => ({
          users: [...s.users, user],
          activeUserId: s.activeUserId ?? user.id,
        })),
      updateUser: (id, patch) => set((s) => ({ users: s.users.map((u) => (u.id === id ? { ...u, ...patch } : u)) })),
      removeUser: (id) =>
        set((s) => {
          const users = s.users.filter((u) => u.id !== id);
          return { users, activeUserId: s.activeUserId === id ? (users[0]?.id ?? null) : s.activeUserId, activeSlot: 0 };
        }),
      setActiveUser: (id) => set({ activeUserId: id, activeSlot: 0 }),
      setActiveSlot: (slot) => set({ activeSlot: slot }),
      addContact: (contact) => set((s) => ({ contacts: [...s.contacts, { chain: currentChain() ?? undefined, ...contact }] })),
      removeContact: (id) => set((s) => ({ contacts: s.contacts.filter((c) => c.id !== id) })),
      // A new submission is followed until a block includes it, on the chain it was sent to.
      addSubmitted: (tx) =>
        set((s) => ({ submitted: [{ state: 'pending' as const, chain: currentChain() ?? undefined, ...tx }, ...s.submitted.filter((x) => x.id !== tx.id)].slice(0, 200) })),
      updateSubmitted: (id, patch) => set((s) => ({ submitted: s.submitted.map((x) => (x.id === id ? { ...x, ...patch } : x)) })),
      keepOutbox: (id, entry) => set((s) => ({ outbox: { ...s.outbox, [id]: { ...entry, twin: s.outbox[id]?.twin } } })),
      pairOutbox: (paid, unpaid) => set((s) => (s.outbox[paid] ? { outbox: { ...s.outbox, [paid]: { ...s.outbox[paid], twin: unpaid } } } : {})),
      forgetOutbox: (id) =>
        set((s) => {
          if (!(id in s.outbox)) return {};
          const outbox = { ...s.outbox };
          delete outbox[id];
          return { outbox };
        }),
      noteNonce: (key, note) => set((s) => ({ nonces: { ...s.nonces, [key]: note } })),
      addSignature: (note) => set((s) => ({ signatures: [note, ...s.signatures].slice(0, 200) })),
      clearSignatures: () => set({ signatures: [] }),
    }),
    {
      name: STORE_NAME,
      version: 7,
      storage: createJSONStorage(() => storage),
      partialize: (s) => ({
        settings: s.settings,
        keys: s.keys,
        users: s.users,
        contacts: s.contacts,
        submitted: s.submitted,
        activeUserId: s.activeUserId,
        activeSlot: s.activeSlot,
        activeCoin: s.activeCoin,
        nonces: s.nonces,
        signatures: s.signatures,
        outbox: s.outbox,
      }),
      // A state saved in another format is not this app's to convert: it is reported, never overwritten.
      migrate: (_persisted, version) => {
        throw new Error(`the saved data is from another version of Ting ID (format ${version})`);
      },
      // The first screen shows once the last chain's cache is read too, so it starts with the last values.
      onRehydrateStorage: () => (_state, error) => {
        if (error) {
          useStore.setState({ loadError: error instanceof Error ? error.message : String(error) });
          return;
        }
        writable = true;
        openLastCache().finally(() => useStore.setState({ hydrated: true }));
      },
    },
  ),
);

/** Everything Ting ID keeps in app storage: the state, the chain caches and the activity. Keys live in the device keystore. */
export async function removeSavedData(): Promise<void> {
  const names = await AsyncStorage.getAllKeys();
  await AsyncStorage.multiRemove(names.filter((n) => n.startsWith(STORE_NAME)));
}

/** All saved data and the chain caches go, and the app starts empty but for `keep`: keys whose secret is still in the keystore. */
export async function clearEverything(keep: KeyMeta[]): Promise<void> {
  forgetCaches();
  await removeSavedData();
  useStore.setState({ ...emptyData(), keys: keep });
}

/**
 * The keys listed in saved data Ting ID cannot load, where they still read as
 * keys: starting fresh keeps them listed, so that no secret in the keystore is
 * left without its entry.
 */
async function listedKeys(): Promise<KeyMeta[]> {
  try {
    const text = await AsyncStorage.getItem(STORE_NAME);
    const keys: unknown = text ? JSON.parse(text)?.state?.keys : null;
    if (!Array.isArray(keys)) return [];
    return keys.filter(
      (k): k is KeyMeta =>
        !!k && typeof k.id === 'string' && typeof k.name === 'string' && SCHEMES.includes(k.scheme) && typeof k.pub === 'string' && isHex(k.pub) && typeof k.createdAt === 'number',
    );
  } catch {
    return [];
  }
}

/**
 * After a load error, the person chose to start over: the saved data goes and
 * the app starts empty but for the keys it still lists.
 */
export async function startFresh(): Promise<void> {
  const keys = await listedKeys();
  await removeSavedData();
  writable = true;
  useStore.setState({ ...emptyData(), keys, loadError: null });
  await openLastCache();
  useStore.setState({ hydrated: true });
}

/** The contacts saved on the chain the app is on. */
export function useContactsHere(): Contact[] {
  const contacts = useStore((s) => s.contacts);
  const chain = useChainCache()?.chain ?? null;
  return useMemo(() => contacts.filter((c) => !c.chain || c.chain === chain), [contacts, chain]);
}

export function useActiveUser(): User | null {
  const users = useStore((s) => s.users);
  const id = useStore((s) => s.activeUserId);
  return users.find((u) => u.id === id) ?? users[0] ?? null;
}

export function nextColor(users: User[]): string {
  return USER_COLORS[users.length % USER_COLORS.length];
}

/** A stable color for a coin, from its contract id. */
export function coinColor(id: string): string {
  return USER_COLORS[parseInt(id.slice(0, 2), 16) % USER_COLORS.length];
}

// Every transaction a node accepts is kept until a block includes it (state/pending).
setSubmitListener((id, tx, notBefore) => useStore.getState().keepOutbox(id, { raw: hex(tx), notBefore, at: Math.floor(Date.now() / 1000) }));
