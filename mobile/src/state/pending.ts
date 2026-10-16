// What became of the transactions this device submitted (`submitted` in the
// store, with their bytes in `outbox`): waiting in the node's pool, held for a
// later time, sent again because the node lost it, failed, or included. The
// app asks the node about each unsettled one on every block and every 20
// seconds. A lost one goes out again with the same bytes, which is safe: its
// nonce lets the chain apply it at most once.
import { useEffect, useMemo, useState } from 'react';
import type { ChainApi } from '@/chain/node';
import { nowSeconds } from '@/chain/format';
import { unhex } from '@/chain/bytes';
import { useChainCache } from './cache';
import { useNodeClient } from './chain';
import { useStore } from './store';
import type { SubmittedTx } from './types';
import { PendingItem, Watch, decide } from './watch';

export { onTheWay } from './watch';
export type { PendingItem, Watch } from './watch';

const POLL_MS = 20_000;
/** Bytes of transactions no submission follows, such as one never shown in the list, are dropped after this. */
const OUTBOX_AGE = 7 * 86_400;

/** Submissions being looked at right now, so two screens do not send one twice. */
const busy = new Set<string>();

async function check(node: ChainApi, tx: SubmittedTx, tip: number): Promise<Watch | null> {
  if (busy.has(tx.id)) return null;
  busy.add(tx.id);
  const store = useStore.getState();
  try {
    const kept = store.outbox[tx.id];
    const lookup = await node.transaction(tx.id);
    const twin = kept?.twin ? { lookup: await node.transaction(kept.twin), kept: store.outbox[kept.twin] } : undefined;
    const d = decide(tx, lookup, kept, nowSeconds(), tip, twin);
    if ('settle' in d) {
      store.updateSubmitted(tx.id, d.settle === 'included' ? { state: 'included', height: d.height, problem: undefined } : { state: 'failed', height: d.height, problem: d.reason });
      store.forgetOutbox(tx.id);
      if (kept?.twin) store.forgetOutbox(kept.twin);
      return null;
    }
    if ('resend' in d) {
      const sent = await Promise.all(d.resend.map((e) => node.submit(unhex(e.raw), e.notBefore).then(() => null, (err: Error) => err)));
      const refused = sent.find((r) => r !== null);
      if (sent.some((r) => r === null)) return { kind: 'resent' };
      store.updateSubmitted(tx.id, { state: 'failed', problem: `the node lost it and refused it when sent again: ${refused!.message}` });
      return null;
    }
    return d.watch;
  } catch {
    // The node did not answer; the next round tries again.
    return null;
  } finally {
    busy.delete(tx.id);
  }
}

/** A submission marked failed because the node refused it may still have gone in: it counts as done once a block has it. */
async function recheck(node: ChainApi, tx: SubmittedTx, tip: number) {
  const store = useStore.getState();
  const kept = store.outbox[tx.id];
  const lookup = await node.transaction(tx.id).catch(() => null);
  const twin = kept?.twin ? { lookup: await node.transaction(kept.twin).catch(() => null), kept: store.outbox[kept.twin] } : undefined;
  const d = decide(tx, lookup, kept, nowSeconds(), tip, twin);
  if ('settle' in d && d.settle === 'included') store.updateSubmitted(tx.id, { state: 'included', height: d.height, problem: undefined });
}

/**
 * The active user's unsettled submissions and failures to show, newest first,
 * kept up to date against the node; with Retry and Dismiss.
 */
export function usePending(height: number | undefined) {
  const node = useNodeClient();
  const userId = useStore((s) => s.activeUserId);
  const submitted = useStore((s) => s.submitted);
  const outbox = useStore((s) => s.outbox);
  const chain = useChainCache()?.chain ?? null;
  const [watches, setWatches] = useState<Record<string, Watch>>({});
  // Only what was sent to this chain: another chain's nodes would not know it, and sending it there again is no use.
  const here = (t: SubmittedTx) => t.userId === userId && (!t.chain || t.chain === chain);
  const open = useMemo(() => submitted.filter((t) => here(t) && (t.state === 'pending' || t.state === 'failed')), [submitted, userId, chain]); // eslint-disable-line react-hooks/exhaustive-deps
  const pendingIds = open.filter((t) => t.state === 'pending' || t.height === undefined).map((t) => t.id).join(',');

  useEffect(() => {
    if (!pendingIds) return;
    // A round's findings are kept even when it ends after the screen changed:
    // they are about the submissions, which do not change with it.
    const round = async () => {
      const mine = useStore.getState().submitted.filter(here);
      const tip = height ?? 0;
      await Promise.all(mine.filter((t) => t.state === 'failed' && t.height === undefined).map((t) => recheck(node, t, tip)));
      const found = await Promise.all(mine.filter((t) => t.state === 'pending').map(async (t) => [t.id, await check(node, t, tip)] as const));
      setWatches((w) => {
        const next = { ...w };
        for (const [id, watch] of found) if (watch) next[id] = watch;
        return next;
      });
    };
    round();
    const timer = setInterval(round, POLL_MS);
    return () => clearInterval(timer);
  }, [node, userId, chain, pendingIds, height]); // eslint-disable-line react-hooks/exhaustive-deps

  // Bytes nothing follows any more are dropped after a while.
  useEffect(() => {
    const store = useStore.getState();
    const followed = new Set(store.submitted.filter((t) => t.state === 'pending' || t.state === 'failed').map((t) => t.id));
    const old = nowSeconds() - OUTBOX_AGE;
    for (const [id, entry] of Object.entries(outbox)) if (!followed.has(id) && entry.at < old) store.forgetOutbox(id);
  }, [outbox]);

  const items: PendingItem[] = open.map((tx) => ({
    tx,
    watch: tx.state === 'failed' ? { kind: 'failed', reason: tx.problem ?? 'it failed', height: tx.height ?? null } : (watches[tx.id] ?? { kind: 'checking' }),
    canRetry: tx.id in outbox,
  }));

  return {
    items,
    /** Sends a failed or lost submission again, with the same bytes. */
    retry: async (id: string): Promise<string | null> => {
      const kept = useStore.getState().outbox[id];
      if (!kept) return 'the app no longer has this transaction';
      try {
        await node.submit(unhex(kept.raw), kept.notBefore);
        useStore.getState().updateSubmitted(id, { state: 'pending', problem: undefined, height: undefined });
        setWatches((w) => ({ ...w, [id]: { kind: 'resent' } }));
        return null;
      } catch (e) {
        useStore.getState().updateSubmitted(id, { state: 'failed', problem: `the node refused it: ${(e as Error).message}` });
        return (e as Error).message;
      }
    },
    /** Puts a submission away: the app stops following it. */
    dismiss: (id: string) => {
      useStore.getState().updateSubmitted(id, { state: 'dismissed' });
      useStore.getState().forgetOutbox(id);
    },
  };
}
