// Release versions, as GitHub tags them and app.json carries them.

/** `v1.2.3` or `1.2.3` as numbers to compare; null for anything else. */
export function versionOf(text: string): [number, number, number] | null {
  const m = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(text.trim());
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

/** Whether release `latest` is newer than `mine`. */
export function newer(latest: string, mine: string): boolean {
  const a = versionOf(latest);
  const b = versionOf(mine);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i];
  return false;
}
