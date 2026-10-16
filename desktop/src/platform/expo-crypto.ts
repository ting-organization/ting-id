// Randomness for key generation and ids, from the webview's CSPRNG.
export function getRandomBytes(n: number): Uint8Array {
  const out = new Uint8Array(n);
  crypto.getRandomValues(out);
  return out;
}

export function randomUUID(): string {
  return crypto.randomUUID();
}
