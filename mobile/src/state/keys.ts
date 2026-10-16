// Device keys as the app manages them: made when a user needs one, named
// after what they are for, and shown only to people who switch on the
// advanced settings. Signing picks the right key by itself.
import { Platform } from 'react-native';
import { hex } from '@/chain/bytes';
import { nowSeconds } from '@/chain/format';
import { Scheme, publicKey, randomSecret } from '@/chain/keys';
import { deleteSecret, randomBytes, saveSecret, uuid } from './keystore';
import { clearEverything, useStore } from './store';
import type { KeyMeta, User } from './types';

/** The scheme used when nobody chose one: the cheapest to verify. */
export const DEFAULT_SCHEME: Scheme = 'secp256k1';

/**
 * "Delete everything": every key's secret leaves the keystore, then all saved
 * data and the chain caches go. A key whose secret the keystore did not delete
 * stays listed, so that deleting can be tried again; their names come back.
 */
export async function deleteEverything(): Promise<string[]> {
  const kept: KeyMeta[] = [];
  for (const key of useStore.getState().keys) {
    try {
      await deleteSecret(key.id);
    } catch {
      kept.push(key);
    }
  }
  await clearEverything(kept);
  return kept.map((k) => k.name);
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
