// Following submitted transactions: what the app makes of the node's answer.
import { describe, expect, it } from 'vitest';
import type { TransactionLookup } from '../src/chain/node';
import type { SubmittedTx } from '../src/state/types';
import { SETTLE_DEPTH, decide, onTheWay } from '../src/state/watch';

const tx = (at: number, extra: Partial<SubmittedTx> = {}): SubmittedTx => ({ id: 'aa', at, summary: 'Send 1 DLR', userId: 'u', state: 'pending', ...extra });
const receipt = (statusName: string) => ({ status: statusName === 'Success' ? 0 : 3, statusName, used: [0, 0, 0, 0], result: new Uint8Array(), events: [] });
const lookup = (l: Partial<TransactionLookup>): TransactionLookup => ({ pending: false, deferred: null, occurrences: [], ...l });
const kept = (at: number, notBefore?: number) => ({ raw: '00', at, notBefore });

describe('following a submission', () => {
  it('settles once a block includes it, as done or failed', () => {
    expect(decide(tx(1000), lookup({ occurrences: [{ height: 102, receipt: receipt('Success') }] }), kept(1000), 1100, 200)).toEqual({ settle: 'included', height: 102 });
    const failed = decide(tx(1000), lookup({ occurrences: [{ height: 103, receipt: receipt('Revert') }] }), kept(1000), 1100, 200);
    expect(failed).toMatchObject({ settle: 'failed', height: 103 });
  });

  it('waits while the node holds it, in the pool or for later', () => {
    expect(decide(tx(1000), lookup({ pending: true }), kept(1000), 1100, 200)).toEqual({ watch: { kind: 'waiting' } });
    const later = lookup({ deferred: { notBefore: 5000, expires: 9000, admitted: false, lastResult: 'Revert' } });
    expect(decide(tx(1000), later, kept(1000, 5000), 1100, 200)).toEqual({ watch: { kind: 'scheduled', notBefore: 5000, lastResult: 'Revert' } });
    const due = lookup({ pending: true, deferred: { notBefore: 1000, expires: 9000, admitted: true, lastResult: null } });
    expect(decide(tx(1000), due, kept(1000, 1000), 1100, 200)).toEqual({ watch: { kind: 'waiting' } });
  });

  it('sends it again when the node lost it, while that makes sense', () => {
    // Just sent: the node may not have it yet.
    expect(decide(tx(1000), null, kept(1000), 1060, 200)).toEqual({ watch: { kind: 'checking' } });
    expect(decide(tx(1000), null, kept(1000), 1200, 200)).toEqual({ resend: [kept(1000)] });
    // A deferred one is sent again until a day past its time.
    expect(decide(tx(1000), null, kept(1000, 50_000), 120_000, 200)).toEqual({ resend: [kept(1000, 50_000)] });
    // After a day, or without a copy, it is lost.
    expect(decide(tx(1000), null, kept(1000), 1000 + 86_401)).toEqual({ watch: { kind: 'lost' } });
    expect(decide(tx(1000), null, undefined, 1200, 200)).toEqual({ watch: { kind: 'lost' } });
  });

  it('waits for a few blocks on top before it counts as done', () => {
    const inBlock = lookup({ occurrences: [{ height: 198, receipt: receipt('Success') }] });
    expect(decide(tx(1000), inBlock, kept(1000), 1100, 200)).toEqual({ watch: { kind: 'confirming', height: 198 } });
    expect(decide(tx(1000), inBlock, kept(1000), 1100, 198 + SETTLE_DEPTH)).toEqual({ settle: 'included', height: 198 });
  });

  it('counts a paid operation done when either the batch or the operation alone goes in', () => {
    const twin = (l: TransactionLookup | null, k = kept(1000)) => ({ lookup: l, kept: k });
    const done = lookup({ occurrences: [{ height: 150, receipt: receipt('Success') }] });
    const failed = lookup({ occurrences: [{ height: 150, receipt: receipt('Revert') }] });
    // The operation went in alone; the batch then failed, as it should.
    expect(decide(tx(1000), failed, kept(1000), 1100, 200, twin(done))).toEqual({ settle: 'included', height: 150 });
    // The batch failed, the operation alone still waits: so does the submission.
    expect(decide(tx(1000), failed, kept(1000), 1100, 200, twin(lookup({ pending: true })))).toEqual({ watch: { kind: 'waiting' } });
    // Both failed: the batch's reason stands.
    expect(decide(tx(1000), failed, kept(1000), 1100, 200, twin(failed))).toMatchObject({ settle: 'failed', height: 150 });
    // Both lost: both are sent again.
    expect(decide(tx(1000), null, kept(1000), 1200, 200, twin(null, kept(1001)))).toEqual({ resend: [kept(1000), kept(1001)] });
  });

  it('counts what is on the way from one account', () => {
    const items = [
      { tx: tx(1, { coin: 'c', account: 'a', amount: '10' }), watch: { kind: 'waiting' as const }, canRetry: true },
      { tx: tx(2, { coin: 'c', account: 'a', amount: '5' }), watch: { kind: 'resent' as const }, canRetry: true },
      { tx: tx(3, { coin: 'c', account: 'a', amount: '7' }), watch: { kind: 'failed' as const, reason: 'x', height: null }, canRetry: false },
      { tx: tx(4, { coin: 'c', account: 'b', amount: '9' }), watch: { kind: 'waiting' as const }, canRetry: true },
      { tx: tx(5), watch: { kind: 'waiting' as const }, canRetry: true },
      // In a block already: the balance shows it.
      { tx: tx(6, { coin: 'c', account: 'a', amount: '3' }), watch: { kind: 'confirming' as const, height: 9 }, canRetry: true },
    ];
    expect(onTheWay(items, 'c', 'a')).toEqual({ count: 2, amount: 15n });
  });
});
