// Shows a file the app has checked, such as the one a statement covers: on a
// phone through the system's sheet to open or keep it, in the browser in a new
// tab (or as a download). On a phone the file goes to the app's cache only.
import { Platform } from 'react-native';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

/** A file name safe to write: no folders, no characters a file system refuses. */
function safeName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim();
  return cleaned || 'file';
}

/**
 * Kinds the browser may show in a tab. A blob opens on the app's own origin,
 * where a page could reach the app's storage, so anything that can run script
 * (HTML, SVG and the like) is only ever downloaded.
 */
const SHOWABLE = /^(application\/pdf|image\/(png|jpeg|gif|webp)|text\/plain)$/;

export async function openFile(bytes: Uint8Array, name: string, mediaType: string): Promise<void> {
  if (Platform.OS === 'web') {
    const showable = SHOWABLE.test(mediaType);
    const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: showable ? mediaType : 'application/octet-stream' }));
    // A new tab when the browser allows one and the kind is safe to show, a download otherwise.
    if (!showable || !window.open(url, '_blank')) {
      const a = document.createElement('a');
      a.href = url;
      a.download = safeName(name);
      a.click();
    }
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    return;
  }
  const file = new File(Paths.cache, safeName(name));
  file.create({ overwrite: true });
  file.write(bytes);
  await Sharing.shareAsync(file.uri, { mimeType: mediaType, dialogTitle: name });
}
