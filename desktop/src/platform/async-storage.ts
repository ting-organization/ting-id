// The persisted app state (users, contacts, settings; never secrets) lives in
// a JSON file in the app data directory, written by the Rust side.
import { invoke, isTauri } from './tauri';

const AsyncStorage = {
  async getItem(key: string): Promise<string | null> {
    if (!isTauri) return localStorage.getItem(key);
    return (await invoke<string | null>('state_get', { key })) ?? null;
  },
  async setItem(key: string, value: string): Promise<void> {
    if (!isTauri) {
      localStorage.setItem(key, value);
      return;
    }
    await invoke('state_set', { key, value });
  },
  async removeItem(key: string): Promise<void> {
    if (!isTauri) {
      localStorage.removeItem(key);
      return;
    }
    await invoke('state_remove', { key });
  },
  async getAllKeys(): Promise<string[]> {
    if (!isTauri) return Object.keys(localStorage);
    return invoke<string[]>('state_keys');
  },
  async multiRemove(keys: string[]): Promise<void> {
    for (const key of keys) await AsyncStorage.removeItem(key);
  },
};

/**
 * The person chose to start fresh after the state file could not be read:
 * the Rust side sets the unreadable file aside, and saving works again.
 */
export async function setUnreadableStateAside(): Promise<void> {
  if (isTauri) await invoke('state_start_fresh');
}

export default AsyncStorage;
