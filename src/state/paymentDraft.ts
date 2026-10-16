// The payment the Send screen hands its review page: what the page shows,
// and the submit that sends it. Send stays open beneath the page, so the
// submit it made still holds its accounts, fee and signer.
import { create } from 'zustand';
import type { CoinInfo } from './coins';

export interface PaymentDraft {
  coin: CoinInfo;
  amount: bigint;
  from: { label: string; account: string };
  to: { account: string };
  /** When it runs: null as soon as a block includes it; else the later of the chosen time and the end of the account's lock. */
  runsAt: number | null;
  /** The chosen time, for a payment set for later. */
  chosenAt: number | null;
  /** How long the sending account's withdrawals wait, 0 for an instant one. */
  lock: number;
  /** E.g. "Fee 0.0001 DLR from Spending"; null for none. */
  fee: string | null;
  /** What a scheduled payment pays its runner, in the coin sent, reserved until it runs. */
  runFee: bigint;
  /** What leaves the sending account in the coin sent: the amount, a fee it pays itself and the run fee. */
  total: bigint;
  /** What the sending account has available before it. */
  available: bigint;
  /** Sends it; resolves with the transaction's id. */
  submit(): Promise<string>;
}

export const usePaymentDraft = create<{ draft: PaymentDraft | null; setDraft(draft: PaymentDraft | null): void }>((set) => ({
  draft: null,
  setDraft: (draft) => set({ draft }),
}));
