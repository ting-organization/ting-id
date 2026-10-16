// The fee an operation pays and which of the user's instant accounts pays it.
// Fees go in the fee coin (Settings), which miners accept, whatever coin the
// operation moves: the account acting pays when it is instant and holds enough,
// else Spending. An action of the paying account in the fee coin carries the
// fee itself; anything else goes in a batch with a payment bound to it. When no
// instant account has enough of the fee coin, the fee falls back to the coin
// the operation moves, with no amount until the user enters one. A delayed
// account never pays.
import { useMemo, useState } from 'react';
import { hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import type { FeePayer } from '@/chain/fees';
import { formatAmount, parseAmount } from '@/chain/format';
import { knownCoinsFor } from '@/config/known-coins';
import { UserAccount, resolveSigner } from './chain';
import { CoinInfo, useAccountViews } from './coins';
import { linkedDeviceKeys } from './registry';
import { defaultPayer, runTip } from './payer';
import { useStore } from './store';
import type { useWorkspace } from './workspace';

type Workspace = ReturnType<typeof useWorkspace>;

/** The (i) text of every fee row. */
export const FEE_INFO =
  'Miners take transactions by what they pay, in the coins they accept. The fee reaches the miner only together with this operation. Choose the coin and the usual amount in Settings. If this device may be stolen, pay from an account the thief cannot sign for.';

/**
 * The coin fees are paid in: the one chosen in Settings while it is followed,
 * else the chain's listed coin when followed, else the first followed coin.
 */
export function feeCoinOf(chosen: string | null, coins: CoinInfo[], chainHex: string | null): CoinInfo | null {
  const followed = (h: string) => coins.find((c) => c.hex === h) ?? null;
  if (chosen && followed(chosen)) return followed(chosen);
  for (const k of chainHex ? knownCoinsFor(chainHex) : []) if (followed(k.contract)) return followed(k.contract);
  return coins[0] ?? null;
}

/** The (i) text a fee row adds when the operation is scheduled. */
export const RUN_INFO = 'A scheduled action pays the same again when it runs, in the coin it moves, to the miner that runs it; that part is reserved until then.';

/** An amount typed for coin `c` in base units; null when it is not one. */
export function feeAmount(text: string, c: CoinInfo): bigint | null {
  try {
    return parseAmount(text.trim() || '0', c.config.decimals);
  } catch {
    return null;
  }
}

/** The operation's own coin and account, when it has them (an identity operation has neither). */
export interface Acting {
  coin: CoinInfo;
  account: UserAccount;
}

export interface PayerOption {
  account: UserAccount;
  coin: CoinInfo;
  /** Available balance; null until read. */
  available: bigint | null;
}

export interface FeeChoice {
  amount: string;
  setAmount(text: string): void;
  /** Every instant account of the user, in the fee coin and in the operation's coin. */
  options: PayerOption[];
  payer: PayerOption | null;
  choose(option: PayerOption): void;
  /** Whether the options span two coins. */
  multiCoin: boolean;
  /** The fee in the payer's coin, base units; 0 for none. */
  fee: bigint;
  /** The fee goes inside the acting account's own action: it pays in the coin it moves. */
  own: boolean;
  /** The fee goes in a batch with the operation: a second signature, about 60% more room in a block. */
  bundled: boolean;
  problem: string | null;
  /** Why the fee is not in the fee coin, when it fell back. */
  notice: string | null;
  /** E.g. "Fee 0.0001 DLR from Spending", when there is a fee. */
  summary: string | null;
  /** Whether this device can sign for a paying account. */
  canPay: boolean;
  /** The payer to submit with: null for no fee; throws on a problem. */
  resolve(): Promise<FeePayer | null>;
}

const optionKey = (o: { account: UserAccount; coin: CoinInfo }) => `${o.account.slot}:${o.coin.hex}`;

/** The fee coin chosen in Settings, resolved against the followed coins. */
export function useFeeCoin(w: Workspace): CoinInfo | null {
  const chosen = useStore((s) => s.settings.feeCoin);
  return feeCoinOf(chosen, w.coins, w.info ? hex(w.info.status.chainId) : null);
}

export function useFee(w: Workspace, acting: Acting | null): FeeChoice {
  const usualText = useStore((s) => s.settings.fee);
  const feeCoin = useFeeCoin(w);
  const [choice, setChoice] = useState<string | null>(null);
  const [typed, setTyped] = useState<string | null>(null);
  const instant = useMemo(() => w.accounts.filter((a) => a.delay === 0), [w.accounts]);
  const actingCoin = acting?.coin ?? null;
  const coins = useMemo(() => {
    const out = feeCoin ? [feeCoin] : [];
    if (actingCoin && actingCoin.hex !== feeCoin?.hex) out.push(actingCoin);
    return out;
  }, [feeCoin, actingCoin]);

  // Balances of the paying candidates, the same ones the wallet shows.
  const { views } = useAccountViews(coins, instant);
  const availableOf = (a: UserAccount, c: CoinInfo) => {
    const view = views.get(c.hex)?.find((v) => v.account.slot === a.slot);
    return view ? coin.available(view.record) : null;
  };

  const options: PayerOption[] = instant.flatMap((a) => coins.map((c) => ({ account: a, coin: c, available: availableOf(a, c) })));
  const usual = feeCoin ? (feeAmount(usualText, feeCoin) ?? 0n) : 0n;
  const spending = instant.find((a) => a.slot === 0) ?? instant[0];
  const picked = defaultPayer(
    options.map((o) => ({ slot: o.account.slot, coin: o.coin.hex, available: o.available })),
    {
      feeCoin: feeCoin?.hex ?? null,
      actingCoin: actingCoin?.hex ?? null,
      actingSlot: acting && acting.account.delay === 0 ? acting.account.slot : null,
      spendingSlot: spending?.slot ?? null,
      usual,
    },
  );
  const payer = (choice && options.find((o) => optionKey(o) === choice)) || (options[picked.index] ?? null);
  const inUsualCoin = !!payer && !!feeCoin && payer.coin.hex === feeCoin.hex;
  // No instant account holds the usual fee in the fee coin: no fee unless the user enters one.
  const short = picked.short;
  const notice =
    short && !choice && payer
      ? inUsualCoin
        ? `${payer.account.label} has too little ${feeCoin!.config.symbol} for the usual fee.`
        : `${spending?.label ?? 'No account'} has too little ${feeCoin!.config.symbol} for the fee; it can be paid in ${payer.coin.config.symbol} instead.`
      : null;

  const canSign = (() => {
    if (!w.user || !w.info) return false;
    if (w.user.kind === 'key') return w.keys.some((k) => k.id === w.user!.keyId);
    return !!w.identity && linkedDeviceKeys(w.info.canonical, w.identity, w.keys).length > 0;
  })();
  const canPay = canSign && payer !== null;

  const amount = typed ?? (inUsualCoin && (!short || choice !== null) ? usualText : '');
  const decimals = payer?.coin.config.decimals ?? 0;
  const symbol = payer?.coin.config.symbol ?? '';
  const parsed = payer ? feeAmount(amount, payer.coin) : 0n;
  const fee = parsed ?? 0n;
  const own = !!acting && !!payer && acting.account.delay === 0 && payer.coin.hex === acting.coin.hex && payer.account.slot === acting.account.slot;
  let problem: string | null = null;
  if (parsed === null) problem = `enter a fee with at most ${decimals} decimals`;
  else if (fee > 0n && !canPay) problem = 'no account here can pay a fee';
  else if (fee > 0n && payer?.available != null && fee > payer.available)
    problem = `the fee needs ${formatAmount(fee, decimals)} ${symbol}; ${payer.account.label} has ${formatAmount(payer.available, decimals)}`;
  const summary = fee > 0n && payer ? `Fee ${formatAmount(fee, decimals)} ${symbol} from ${payer.account.label}` : null;

  return {
    amount,
    setAmount: setTyped,
    options,
    payer,
    choose: (o) => {
      // An amount typed for another coin means nothing in this one.
      if (o.coin.hex !== payer?.coin.hex) setTyped(null);
      setChoice(optionKey(o));
    },
    multiCoin: coins.length > 1,
    fee,
    own,
    bundled: fee > 0n && !own,
    problem,
    notice,
    summary,
    canPay,
    resolve: async () => {
      if (fee === 0n) return null;
      if (problem) throw new Error(problem);
      if (!payer || !w.user || !w.info) throw new Error('no account here can pay a fee');
      const signer = await resolveSigner(w.user, payer.account.slot, w.info.canonical, w.keys, w.identity);
      return { signer, coinId: payer.coin.id, fee };
    },
  };
}

/** Appends the fee, if any, to a summary of what was submitted. */
export function withFee(summary: string, fee: FeeChoice): string {
  return fee.summary ? `${summary} · ${fee.summary}` : summary;
}

/**
 * The run fee of an action from a time-locked account, such as a mint: what
 * whoever runs it when due is paid, the fee amount again, in the action's coin
 * from the acting account (wiki: programs/coin). An account holding less of
 * that coin pays none.
 */
export function useRunFee(fee: FeeChoice, c: CoinInfo | null, account: UserAccount | null): bigint {
  const { views } = useAccountViews(c ? [c] : [], account ? [account] : []);
  if (!c || !account || account.delay === 0) return 0n;
  const view = views.get(c.hex)?.[0];
  const tip = runTip(fee.amount, c);
  const ownFee = fee.own ? fee.fee : 0n;
  return view && coin.available(view.record) >= tip + ownFee ? tip : 0n;
}
