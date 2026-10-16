// Which instant account pays an operation's fee unless the user picks one
// (state/fees): in the fee coin, the acting account first, then Spending, then
// the others, the first holding the usual fee; when none does, the acting
// account or Spending in the coin the operation moves.
import { parseAmount } from '@/chain/format';

export interface PayerCandidate {
  slot: number;
  /** The coin, hex. */
  coin: string;
  /** Available balance; null until read, which counts as enough. */
  available: bigint | null;
}

export interface PayerContext {
  feeCoin: string | null;
  actingCoin: string | null;
  /** The acting account's slot when it is instant; null otherwise. */
  actingSlot: number | null;
  spendingSlot: number | null;
  /** The usual fee in the fee coin. */
  usual: bigint;
}

/**
 * The default payer's index in `options`, or -1 for none, and whether no
 * account holds the usual fee in the fee coin.
 */
export function defaultPayer(options: PayerCandidate[], ctx: PayerContext): { index: number; short: boolean } {
  const order = (slot: number) => (slot === ctx.actingSlot ? 0 : slot === ctx.spendingSlot ? 1 : 2);
  const ranked = (coin: string | null) =>
    options
      .map((o, index) => ({ o, index }))
      .filter(({ o }) => coin !== null && o.coin === coin)
      .sort((a, b) => order(a.o.slot) - order(b.o.slot) || a.o.slot - b.o.slot);
  const inFeeCoin = ranked(ctx.feeCoin);
  const enough = inFeeCoin.find(({ o }) => o.available === null || o.available >= ctx.usual);
  if (enough) return { index: enough.index, short: false };
  const short = ctx.feeCoin !== null && ctx.usual > 0n;
  const fallback = ranked(ctx.actingCoin)[0] ?? inFeeCoin[0];
  return { index: fallback ? fallback.index : -1, short };
}

/**
 * What a scheduled action pays the miner that runs it, in the coin `c` it
 * moves: the fee's amount again, rounded up to the coin's smallest unit;
 * nothing when the fee is none or not an amount.
 */
export function runTip(feeText: string, c: { config: { decimals: number } }): bigint {
  let v: bigint;
  try {
    v = parseAmount(feeText.trim() || '0', 18);
  } catch {
    return 0n;
  }
  const d = c.config.decimals;
  if (d >= 18) return v * 10n ** BigInt(d - 18);
  const unit = 10n ** BigInt(18 - d);
  return (v + unit - 1n) / unit;
}
