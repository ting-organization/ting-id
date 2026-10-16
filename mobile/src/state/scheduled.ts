// Acting on the user's scheduled actions, wherever a screen lists them: cancel
// (one signature per account and coin, any number of actions), run one that is
// due, or hand its run to the node for when it falls due.
import { useRef, useState } from 'react';
import type * as coin from '@/chain/coin';
import { nowSeconds } from '@/chain/format';
import { UserAccount, queueRun, resolveSigner, submitCoinCancel, submitRun } from './chain';
import type { CoinInfo } from './coins';
import { FeeChoice, withFee } from './fees';
import { useStore } from './store';
import type { useWorkspace } from './workspace';

type Workspace = ReturnType<typeof useWorkspace>;

/** A scheduled action of one of the user's accounts. */
/** Scheduled actions one cancel takes at most, to stay within a transaction's budget. */
const CANCEL_CHUNK = 16;

export interface ScheduledAction {
  coin: CoinInfo;
  view: { account: UserAccount };
  sequence: bigint;
  record: coin.ScheduledRecord;
}

export function scheduledActionKey(e: ScheduledAction): string {
  return `${e.coin.hex}-${e.view.account.slot}-${e.sequence}`;
}

/**
 * Cancel, run and queue for scheduled actions. `busy` names the action in
 * progress (`cancel-<key>`, `run-<key>`, `queue-<key>`, or the label given to
 * a cancel); `opened` is told the transaction to show.
 */
export function useScheduledActions(w: Workspace, fee: FeeChoice, opened: (txId: string) => void) {
  const addSubmitted = useStore((s) => s.addSubmitted);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // One action at a time: a second button pressed meanwhile does nothing.
  const running = useRef(false);
  const attempt = async (label: string, step: () => Promise<string | null>) => {
    if (running.current) return;
    running.current = true;
    setBusy(label);
    setError(null);
    try {
      const txId = await step();
      if (txId) opened(txId);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      running.current = false;
      setBusy(null);
    }
  };

  return {
    busy,
    error,
    /** Cancels `entries`: one cancel per account and coin, each paying the fee. */
    cancel: (entries: ScheduledAction[], label: string) =>
      attempt(label, async () => {
        if (!w.user || !w.info || entries.length === 0) return null;
        const groups = new Map<string, ScheduledAction[]>();
        for (const e of entries) {
          const k = `${e.coin.hex}:${e.view.account.slot}`;
          groups.set(k, [...(groups.get(k) ?? []), e]);
        }
        let last: string | null = null;
        for (const all of groups.values()) {
          const { coin: c, view } = all[0];
          const signer = await resolveSigner(w.user, view.account.slot, w.info.canonical, w.keys, w.identity);
          // A long list goes in several cancels, each within one transaction's budget.
          for (let i = 0; i < all.length; i += CANCEL_CHUNK) {
            const group = all.slice(i, i + CANCEL_CHUNK);
            const r = await submitCoinCancel(w.node, w.info.status.chainId, c.id, signer, group.map((e) => e.sequence), await fee.resolve());
            const summary =
              group.length === 1 ? `Cancel scheduled action #${group[0].sequence} of ${view.account.label}` : `Cancel ${group.length} scheduled actions of ${view.account.label}`;
            addSubmitted({ id: r.txId, at: nowSeconds(), summary: withFee(`${summary} (${c.config.symbol})`, fee), userId: w.user.id });
            last = r.txId;
          }
        }
        return last;
      }),
    run: (e: ScheduledAction) =>
      attempt(`run-${scheduledActionKey(e)}`, async () => {
        const txId = await submitRun(w.node, e.coin.id, e.view.account.account, e.sequence);
        if (w.user) addSubmitted({ id: txId, at: nowSeconds(), summary: `Run scheduled action #${e.sequence} of ${e.view.account.label}`, userId: w.user.id });
        return txId;
      }),
    queue: (e: ScheduledAction) =>
      attempt(`queue-${scheduledActionKey(e)}`, async () => {
        const txId = await queueRun(w.node, e.coin.id, e.view.account.account, e.sequence, Number(e.record.executeAt));
        if (w.user) addSubmitted({ id: txId, at: nowSeconds(), summary: `Queue run of scheduled action #${e.sequence}`, userId: w.user.id });
        return txId;
      }),
  };
}
