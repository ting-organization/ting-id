// Device keys as the app manages them: made when a user needs one, named
// after what they are for, and shown only to people who switch on the
// advanced settings. Signing picks the right key by itself.
import { Platform } from 'react-native';
import { hex } from '@/chain/bytes';
import { nowSeconds } from '@/chain/format';
import { Scheme, publicKey, randomSecret } from '@/chain/keys';
import { newPhrase, phraseSecret } from '@/chain/phrase';
import { deleteSecret, randomBytes, saveSecret, uuid } from './keystore';
import { deletePhrase, forgetFresh, rememberFresh, savePhrase } from './phrases';
import { forgetScans } from './activity';
import { forgetCoinIndexes } from './coinIndex';
import { StrayPhrase, clearEverything, useStore } from './store';
import type { KeyMeta, User } from './types';

/** The scheme used when nobody chose one: the cheapest to verify. */
export const DEFAULT_SCHEME: Scheme = 'secp256k1';

/**
 * "Delete everything": every key's secret and every recovery phrase leave the
 * keystore, then all saved data and the chain caches go. A key or phrase the
 * keystore did not delete stays listed, so that deleting can be tried again;
 * what stayed comes back by name. Scans and reads still on the way save
 * nothing after.
 */
export async function deleteEverything(): Promise<string[]> {
  const state = useStore.getState();
  const phrases: StrayPhrase[] = [
    ...state.users.filter((u) => u.phrase).map((u) => ({ id: u.id, name: u.name, phrase: u.phrase! })),
    ...state.strayPhrases,
  ];
  for (const user of state.users) forgetFresh(user.id);
  const left: StrayPhrase[] = [];
  for (const p of phrases) {
    try {
      await deletePhrase(p);
    } catch {
      left.push(p);
    }
  }
  const kept: KeyMeta[] = [];
  for (const key of useStore.getState().keys) {
    try {
      await deleteSecret(key.id);
    } catch {
      kept.push(key);
    }
  }
  forgetScans();
  forgetCoinIndexes();
  await clearEverything(kept, left);
  return [...left.map((p) => `the recovery phrase of ${p.name}`), ...kept.map((k) => k.name)];
}

/** Forgets a user on this device, the recovery phrase it keeps included; its keys stay listed. */
export async function forgetUser(user: User): Promise<void> {
  forgetFresh(user.id);
  await deletePhrase(user);
  useStore.getState().removeUser(user.id);
}

/**
 * A new recovery phrase for `user`: its key made and kept, the words kept on
 * this phone in place of any before, and held for the backup screen. The
 * identity lists the key only once a change or a recovery adds it. The new
 * words are written over the old ones, so a save that fails or is cancelled
 * leaves the old words as they were.
 */
export async function newUserPhrase(user: User): Promise<KeyMeta> {
  const words = newPhrase(randomBytes);
  const key = await phraseKey(keyNameFor(user.name), words);
  const phrase = await savePhrase(user.id, words);
  useStore.getState().updateUser(user.id, { phrase, phraseKeyId: key.id, phraseBackedUp: false });
  rememberFresh(user.id, words);
  return key;
}

/**
 * The key a recovery phrase gives, kept on this device: made and stored the
 * first time, the same key when this device has it already.
 */
export async function phraseKey(name: string, phrase: string): Promise<KeyMeta> {
  const secret = await phraseSecret(phrase);
  const pub = hex(publicKey('secp256k1', secret));
  const have = useStore.getState().keys.find((k) => k.scheme === 'secp256k1' && k.pub === pub);
  if (have) {
    await saveSecret(have.id, hex(secret));
    return have;
  }
  const key: KeyMeta = { id: uuid(), name, scheme: 'secp256k1', pub, createdAt: nowSeconds() };
  await saveSecret(key.id, hex(secret));
  useStore.getState().addKey(key);
  return key;
}

/** Makes a key on this device, stores its secret and registers it. */
export async function createDeviceKey(name: string, scheme: Scheme = DEFAULT_SCHEME): Promise<KeyMeta> {
  const secret = randomSecret(scheme, randomBytes);
  const key: KeyMeta = { id: uuid(), name, scheme, pub: hex(publicKey(scheme, secret)), createdAt: nowSeconds() };
  await saveSecret(key.id, hex(secret));
  useStore.getState().addKey(key);
  return key;
}

/** The key this device made for the user, if it still exists. */
export function deviceKeyOf(user: User, keys: KeyMeta[]): KeyMeta | null {
  return keys.find((k) => k.id === user.keyId) ?? null;
}

/** What this device is called in key names: "Anna · iPhone". */
export function deviceLabel(): string {
  switch (Platform.OS) {
    case 'web':
      return 'browser';
    case 'ios':
      return 'iPhone';
    case 'android':
      return 'Android phone';
    default:
      return Platform.OS;
  }
}

export function keyNameFor(userName: string): string {
  return `${userName} · ${deviceLabel()}`;
}

/** The user's device key, made on first use. */
export async function ensureDeviceKey(user: User, keys: KeyMeta[]): Promise<KeyMeta> {
  const existing = deviceKeyOf(user, keys);
  if (existing) return existing;
  const key = await createDeviceKey(keyNameFor(user.name));
  useStore.getState().updateUser(user.id, { keyId: key.id });
  return key;
}
