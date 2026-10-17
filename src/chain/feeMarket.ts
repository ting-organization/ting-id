// What recent transactions paid for their room in a block, and the fee an
// operation pays from that. Miners take transactions by what they pay the
// block's producer per share of the block, a transaction's share being its
// largest fraction of any capacity (wiki: programs/conventions, producer
// payments). So a fee is a rate per whole block times the share an operation
// takes: the median rate of recent paying transactions, times the share
// measured for operations of its kind.
import { equal, hex } from './bytes';
import * as coin from './coin';

/**
 * The share of a block an operation takes, measured on the network: compute
 * decides it, 150M per block. No margin: what one operation pays is the next
 * one's rate, so anything added would compound.
 */
export const OPERATION_SHARE = {
  /** A coin action carrying its own fee, such as a payment: 15.6M. */
  own: 0.104,
  /** An operation with its fee in a batch beside it, such as a payment in another coin or an identity change: 25.2M. */
  bundled: 0.168,
  /** The run of a scheduled action, which pays its own tip: 1.48M. */
  run: 0.0099,
} as const;

/** How many recent paying transactions the median is taken over. */
export const SAMPLE = 50;

/** A transaction's share of a block: its largest fraction of any capacity. */
export function shareOf(used: number[], limits: number[]): number {
  let share = 0;
  limits.forEach((limit, i) => {
    if (limit > 0) share = Math.max(share, (used[i] ?? 0) / limit);
  });
  return share;
}

/** The payout account in producer data: after "OCP1", the first entry of type 1 that is 32 bytes. */
export function payoutAccount(producer: Uint8Array): Uint8Array | null {
  if (producer.length < 4 || String.fromCharCode(...producer.subarray(0, 4)) !== 'OCP1') return null;
  const view = new DataView(producer.buffer, producer.byteOffset, producer.byteLength);
  for (let at = 4; at + 8 <= producer.length; ) {
    const kind = view.getUint32(at, true);
    const length = view.getUint32(at + 4, true);
    if (at + 8 + length > producer.length) return null;
    if (kind === 1 && length === 32) return producer.slice(at + 8, at + 40);
    at += 8 + length;
  }
  return null;
}

export interface PaidBlock {
  height: number;
  producer: Uint8Array;
  limits: number[];
  receipts: { status: number; used: number[]; events: { contract: Uint8Array; payload: Uint8Array }[] }[];
}

/** One paying transaction: what it paid per whole block, in base units of `coin` (hex). */
export interface Rate {
  height: number;
  coin: string;
  perBlock: number;
}

/**
 * What each succeeding transaction of `blocks` paid the block's producer per
 * whole block, by the paying program (a coin); only producer payments to the
 * payout account count, and a block without one counts nothing. A block's
 * reward claim pays the producer too, from coins it issues: that is the
 * reward, not a fee, and is left out.
 */
export function ratesOf(blocks: PaidBlock[]): Rate[] {
  const out: Rate[] = [];
  for (const b of blocks) {
    const payee = payoutAccount(b.producer);
    if (!payee) continue;
    for (const r of b.receipts) {
      if (r.status !== 0) continue;
      const paid = new Map<string, bigint>();
      let issued = false;
      for (const e of r.events) {
        const c = hex(e.contract);
        const ev = coin.decodeCoinEvent(e.payload);
        if (ev?.kind === 'transfer' && equal(ev.from, coin.ZERO_ACCOUNT)) issued = true;
        if (ev?.kind === 'producerPayment' && equal(ev.payee, payee)) paid.set(c, (paid.get(c) ?? 0n) + ev.amount);
      }
      if (issued) continue;
      const share = shareOf(r.used, b.limits);
      if (share <= 0) continue;
      for (const [c, amount] of paid) out.push({ height: b.height, coin: c, perBlock: Number(amount) / share });
    }
  }
  return out;
}

/** The median of the newest `SAMPLE` rates in `coin`, or null when none paid in it. */
export function medianRate(rates: Rate[], coinHex: string): number | null {
  const recent = rates
    .filter((r) => r.coin === coinHex)
    .sort((a, b) => b.height - a.height)
    .slice(0, SAMPLE)
    .map((r) => r.perBlock)
    .sort((a, b) => a - b);
  if (recent.length === 0) return null;
  const mid = recent.length >> 1;
  return recent.length % 2 ? recent[mid] : (recent[mid - 1] + recent[mid]) / 2;
}

/** `v` to two significant digits, the nearest: 9,995 is 10,000 and 16,146 is 16,000. */
function significant(v: number): number {
  const step = 10 ** (Math.floor(Math.log10(v)) - 1);
  return Math.round(v / step) * step;
}

/**
 * The fee for an operation taking `share` of a block at `perBlock`, to two
 * significant digits, the nearest (rounding up would raise the rate a little
 * with every round), and never less than the coin's smallest unit: with no
 * recent paying transaction, that still ranks it above those that pay nothing.
 */
export function feeFor(perBlock: number | null, share: number): bigint {
  if (perBlock === null || !(perBlock * share >= 1)) return 1n;
  return BigInt(Math.max(1, Math.round(significant(perBlock * share))));
}
