// The bridge to the Rust side. Outside a Tauri window (the Vite dev server in
// a browser) the platform modules fall back to web APIs, so the UI can be run
// and tested there; nothing from such a session is a real keystore.
import { invoke as tauriInvoke } from '@tauri-apps/api/core';

export const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

export function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  return tauriInvoke<T>(command, args);
}

/** What this machine is called in key names; the browser fallback says so. */
export function hostLabel(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  if (/Windows/i.test(ua)) return 'Windows PC';
  if (/Mac/i.test(ua)) return 'Mac';
  if (/Linux/i.test(ua)) return 'Linux PC';
  return 'computer';
}
