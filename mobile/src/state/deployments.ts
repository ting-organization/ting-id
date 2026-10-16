// The saved state of the coin index (state/coinIndex): how far it has read
// the chain and the coins it found. Pure, so it can be tested on its own.
import { hex } from '@/chain/bytes';
import type { BlockReceipts } from '@/chain/node';

export interface SavedIndex {
  version: 1;
  /** The next block to read. */
  next: number;
  /** The last block read, to notice when a reorganization replaced it. */
  last: { height: number; id: string } | null;
  /** Coins found, hex ContractIds, in the order they were deployed. */
  coins: string[];
}

export const emptyIndex = (): SavedIndex => ({ version: 1, next: 1, last: null, coins: [] });

export function isSavedIndex(v: unknown): v is SavedIndex {
  const s = v as SavedIndex;
  return !!s && s.version === 1 && Number.isInteger(s.next) && s.next >= 1 && Array.isArray(s.coins) && s.coins.every((c) => typeof c === 'string');
}

/**
 * Results that may be a deployment's ContractId: those of 32 bytes of
 * successful transactions, in block order. A call can return 32 bytes too, so
 * each is checked against the node before it counts.
 */
export function candidates(blocks: BlockReceipts[]): string[] {
  const out: string[] = [];
  for (const b of blocks) for (const r of b.receipts) if (r.status === 0 && r.result.length === 32) out.push(hex(r.result));
  return out;
}

/** Reads `depth` blocks again below the last one read, after a reorganization replaced it or the chain got shorter. */
export function rewind(s: SavedIndex, depth: number, height = Infinity): SavedIndex {
  const from = Math.min(s.last?.height ?? s.next - 1, height);
  return { ...s, next: Math.max(1, from - depth + 1), last: null };
}

/** The index after reading `blocks` (in order, from `s.next`), with the coins among `found` added. */
export function advance(s: SavedIndex, blocks: BlockReceipts[], found: string[]): SavedIndex {
  if (blocks.length === 0) return s;
  const last = blocks[blocks.length - 1];
  const coins = [...s.coins];
  for (const c of found) if (!coins.includes(c)) coins.push(c);
  return { ...s, next: last.height + 1, last: { height: last.height, id: last.id }, coins };
}
