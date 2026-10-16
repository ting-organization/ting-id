// A user's recovery phrase on this phone (RECOVERY.md). The words sit in the
// keystore under `ting.phrase.<user id>`, apart from the key they give:
// signing reads only the key, so it asks for nothing, while showing the words
// always asks the phone to confirm its owner. Where the phone has a strong
// fingerprint or face unlock, the keystore itself refuses to hand out the
// words without it; Android then drops them when a new fingerprint or face
// is added, and only the written copy is left. Elsewhere the app asks for the
// phone's unlock (PIN, pattern or password) before it reads them.
import { Platform } from 'react-native';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import { normalizePhrase } from '@/chain/phrase';
import type { User } from './types';

const PREFIX = 'ting.phrase.';
/** Only on this phone, and only while it is unlocked. */
const BASE: SecureStore.SecureStoreOptions = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };

export type PhraseLock = NonNullable<User['phrase']>;

/** Why the words could not be shown. */
export class PhraseUnavailable extends Error {}

function locked(prompt: string): SecureStore.SecureStoreOptions {
  return { ...BASE, requireAuthentication: true, authenticationPrompt: prompt };
}

/**
 * Words just made, held in memory only, so the backup screen right after can
 * show them without asking the phone again. Gone once written down or when
 * the app closes.
 */
const fresh = new Map<string, string>();

export function rememberFresh(userId: string, words: string): void {
  fresh.set(userId, normalizePhrase(words));
}

export function freshWords(userId: string): string | null {
  return fresh.get(userId) ?? null;
}

export function forgetFresh(userId: string): void {
  fresh.delete(userId);
}

/** Keeps the words of `userId` on this phone; says how they are locked. */
export async function savePhrase(userId: string, phrase: string): Promise<PhraseLock> {
  const words = normalizePhrase(phrase);
  if (Platform.OS === 'web') {
    globalThis.localStorage?.setItem(PREFIX + userId, words);
    return 'device';
  }
  if (SecureStore.canUseBiometricAuthentication()) {
    await SecureStore.setItemAsync(PREFIX + userId, words, locked('Keep your recovery phrase on this phone'));
    return 'biometric';
  }
  await SecureStore.setItemAsync(PREFIX + userId, words, BASE);
  return 'device';
}

/**
 * The words of `user` once the phone confirmed its owner; null when this
 * phone keeps none. Throws PhraseUnavailable when the check fails or is
 * cancelled, or when the phone can no longer read them.
 */
export async function readPhrase(user: User): Promise<string | null> {
  if (!user.phrase) return null;
  if (Platform.OS === 'web') return globalThis.localStorage?.getItem(PREFIX + user.id) ?? null;
  if (user.phrase === 'biometric') {
    let words: string | null;
    try {
      words = await SecureStore.getItemAsync(PREFIX + user.id, locked('Show your recovery phrase'));
    } catch {
      throw new PhraseUnavailable('the phone did not confirm it is you');
    }
    if (words === null) throw new PhraseUnavailable('this phone can no longer show the words, as its fingerprints or face changed; your written copy still works');
    return words;
  }
  await confirmOwner('Show your recovery phrase');
  return SecureStore.getItemAsync(PREFIX + user.id, BASE);
}

/** Removes the words of `user` from this phone. */
export async function deletePhrase(user: User): Promise<void> {
  if (!user.phrase) return;
  if (Platform.OS === 'web') {
    globalThis.localStorage?.removeItem(PREFIX + user.id);
    return;
  }
  await SecureStore.deleteItemAsync(PREFIX + user.id, user.phrase === 'biometric' ? locked('Remove your recovery phrase from this phone') : BASE);
}

/** Whether the phone has a screen lock at all; without one, anyone holding it can see the words. The browser build has none to ask about. */
export async function phoneHasLock(): Promise<boolean> {
  if (Platform.OS === 'web') return true;
  return (await LocalAuthentication.getEnrolledLevelAsync()) !== LocalAuthentication.SecurityLevel.NONE;
}

/** Asks for the phone's own unlock; a phone without one has nothing to ask. */
async function confirmOwner(prompt: string): Promise<void> {
  if (!(await phoneHasLock())) return;
  const r = await LocalAuthentication.authenticateAsync({ promptMessage: prompt });
  if (!r.success) throw new PhraseUnavailable(r.error === 'user_cancel' || r.error === 'system_cancel' || r.error === 'app_cancel' ? 'cancelled' : 'the phone did not confirm it is you');
}
