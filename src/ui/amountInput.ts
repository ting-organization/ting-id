// An amount as it is typed, shown with its whole part in groups of three,
// as the wallet shows amounts: `40000.5` reads `40 000.5`. What was typed
// stays the digits alone, for parseAmount.

/** `raw` with its whole part grouped; anything that is not a plain number shows as typed. */
export function groupTyped(raw: string): string {
  const m = /^(\d*)([.,]?\d*)$/.exec(raw);
  if (!m) return raw;
  return m[1].replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + m[2];
}

/** The typed text without the groups' spaces. */
export function ungroupTyped(text: string): string {
  return text.replace(/[\s_]/g, '');
}
