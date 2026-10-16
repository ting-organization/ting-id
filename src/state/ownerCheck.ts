// The phone's own check of its owner: fingerprint or face, or the screen
// lock's PIN, pattern or password. Ting ID asks it when it opens and before
// a payment goes (Settings → Security), and before it shows a recovery
// phrase. A phone without a screen lock has nothing to ask, nor does the
// browser build.
import { Platform } from 'react-native';
import * as LocalAuthentication from 'expo-local-authentication';

/** Whether the phone can check its owner: it has a screen lock. */
export async function ownerCheckAvailable(): Promise<boolean> {
  if (Platform.OS === 'web') return false;
  return (await LocalAuthentication.getEnrolledLevelAsync()) !== LocalAuthentication.SecurityLevel.NONE;
}

export type OwnerCheck = 'confirmed' | 'cancelled' | 'failed' | 'unavailable';

let running = 0;

/**
 * Whether a check is on screen: the PIN screen is a screen of its own, so
 * the app seems to leave and come back while it shows.
 */
export function ownerCheckRunning(): boolean {
  return running > 0;
}

/** Asks the phone to check its owner; `prompt` titles the check, `detail` says what it is for. */
export async function checkOwner(prompt: string, detail?: string): Promise<OwnerCheck> {
  if (!(await ownerCheckAvailable())) return 'unavailable';
  running++;
  try {
    const r = await LocalAuthentication.authenticateAsync({ promptMessage: prompt, promptSubtitle: detail, cancelLabel: 'Cancel' });
    if (r.success) return 'confirmed';
    return r.error === 'user_cancel' || r.error === 'system_cancel' || r.error === 'app_cancel' ? 'cancelled' : 'failed';
  } finally {
    running--;
  }
}
