// Where a submitted transaction stands, from the node's answer about it and
// the bytes the app kept (state/pending follows them). Pure, for tests.
import type { TransactionLookup } from '@/chain/node';
import type { OutboxEntry, SubmittedTx } from './types';
import { statusWords } from '@/ui/describe';

/** How long after its time a lost transaction is sent again: as long as a node keeps one in its pool. */
const RESEND_WINDOW = 86_400;
/** How long a transaction the node does not know yet counts as just sent. */
const GRACE = 120;
/** Blocks on top of the one that included a transaction before it counts as settled: a shallower reorganization can still take it out. */
export const SETTLE_DEPTH = 3;

/** Where a submission stands. */
export type Watch =
  | { kind: 'checking' }
  | { kind: 'waiting' }
  /** In a block not yet SETTLE_DEPTH deep. */
  | { kind: 'confirming'; height: number }
  | { kind: 'scheduled'; notBefore: number; lastResult: string | null }
  | { kind: 'resent' }
  | { kind: 'lost' }
  | { kind: 'failed'; reason: string; height: number | null };

export interface PendingItem {
  tx: SubmittedTx;
  watch: Watch;
  /** Whether the app still holds the bytes, to send it again. */
  canRetry: boolean;
}

export type Decision =
  | { settle: 'included'; height: number }
  | { settle: 'failed'; height: number; reason: string }
  | { resend: OutboxEntry[] }
  | { watch: Watch };

/** What to make of one transaction, from the node's answer about it and the bytes the app kept. */
function decideOne(sentAt: number, lookup: TransactionLookup | null, kept: OutboxEntry | undefined, now: number): Decision {
  if (lookup && lookup.occurrences.length > 0) {
    const last = lookup.occurrences[lookup.occurrences.length - 1];
    return last.receipt.statusName === 'Success'
      ? { settle: 'included', height: last.height }
      : { settle: 'failed', height: last.height, reason: `it ran in block ${last.height} without succeeding: ${statusWords(last.receipt.statusName)}` };
  }
  if (lookup?.deferred && !lookup.deferred.admitted) return { watch: { kind: 'scheduled', notBefore: lookup.deferred.notBefore, lastResult: lookup.deferred.lastResult } };
  if (lookup && (lookup.pending || lookup.deferred)) return { watch: { kind: 'waiting' } };
  if (now - sentAt < GRACE) return { watch: { kind: 'checking' } };
  if (kept && now < (kept.notBefore ?? kept.at) + RESEND_WINDOW) return { resend: [kept] };
  return { watch: { kind: 'lost' } };
}

/**
 * What to make of a submission, from the node's answer about it and the bytes
 * the app kept; for a batch that pays for an operation, also from the answer
 * about the operation sent alone (`twin`): whichever goes in settles it. A
 * transaction settles once SETTLE_DEPTH blocks follow the one that included
 * it, at chain height `tip`, and so does one that failed in a block. Pure.
 */
export function decide(
  tx: SubmittedTx,
  lookup: TransactionLookup | null,
  kept: OutboxEntry | undefined,
  now: number,
  tip: number,
  twin?: { lookup: TransactionLookup | null; kept: OutboxEntry | undefined },
): Decision {
  const own = decideOne(tx.at, lookup, kept, now);
  const other = twin ? decideOne(tx.at, twin.lookup, twin.kept, now) : null;
  const included = [own, other].find((d): d is { settle: 'included'; height: number } => !!d && 'settle' in d && d.settle === 'included');
  if (included) return tip >= included.height + SETTLE_DEPTH ? included : { watch: { kind: 'confirming', height: included.height } };
  // The batch failed, but the operation alone may still go in.
  if ('settle' in own && other && !('settle' in other)) return other;
  if ('resend' in own) return { resend: [...own.resend, ...(other && 'resend' in other ? other.resend : [])] };
  // A failure in a block settles as deep as an inclusion does: a reorganization can take that block out too.
  if ('settle' in own && own.settle === 'failed' && tip < own.height + SETTLE_DEPTH) return { watch: { kind: 'failed', reason: own.reason, height: own.height } };
  return own;
}

/** Payments from one account in one coin that no block has included yet: how many, and how much. */
export function onTheWay(items: PendingItem[], coinHex: string, accountHex: string): { count: number; amount: bigint } {
  let count = 0;
  let amount = 0n;
  for (const { tx, watch } of items) {
    // In a block already, it shows in the balance.
    if (watch.kind === 'failed' || watch.kind === 'confirming' || tx.coin !== coinHex || tx.account !== accountHex) continue;
    count++;
    amount += BigInt(tx.amount ?? '0');
  }
  return { count, amount };
}
