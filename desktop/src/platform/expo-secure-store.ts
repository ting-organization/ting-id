// Key secrets go to the operating system's keychain through the Rust side
// (Windows Credential Manager, macOS Keychain, the Linux secret service).
import { invoke, isTauri } from './tauri';

/** The phone's options; the desktop keychain takes none. */
export type SecureStoreOptions = { keychainAccessible?: number };
export const WHEN_UNLOCKED_THIS_DEVICE_ONLY = 0;

export async function setItemAsync(key: string, value: string, _options?: SecureStoreOptions): Promise<void> {
  if (!isTauri) {
    localStorage.setItem(key, value);
    return;
  }
  await invoke('secret_set', { id: key, value });
}

export async function getItemAsync(key: string, _options?: SecureStoreOptions): Promise<string | null> {
  if (!isTauri) return localStorage.getItem(key);
  return (await invoke<string | null>('secret_get', { id: key })) ?? null;
}

export async function deleteItemAsync(key: string, _options?: SecureStoreOptions): Promise<void> {
  if (!isTauri) {
    localStorage.removeItem(key);
    return;
  }
  await invoke('secret_delete', { id: key });
}
