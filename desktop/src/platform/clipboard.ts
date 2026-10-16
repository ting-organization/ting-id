// Copying identifiers: the clipboard plugin in the Tauri window, the web API elsewhere.
import { isTauri } from './tauri';

export async function copyText(text: string): Promise<void> {
  if (isTauri) {
    const { writeText } = await import('@tauri-apps/plugin-clipboard-manager');
    await writeText(text);
    return;
  }
  await navigator.clipboard?.writeText(text);
}
