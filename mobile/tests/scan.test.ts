// The activity scan: which events it keeps, how it grows forward and back,
// what survives a reorganization, and what it saves on the device.
import { describe, expect, it } from 'vitest';
import { Writer, hex } from '../src/chain/bytes';
import * as coin from '../src/chain/coin';
import * as idp from '../src/chain/identity';
import type { BlockEvents } from '../src/chain/node';
import { REORG_DEPTH, ScanScope, activityOf, addNewer, addOlder, emptyScan, isSavedScan, matchBlocks, rollBack, scopeKey, trimmed } from '../src/state/scan';

const fill = (b: number) => new Uint8Array(32).fill(b);
const COIN = fill(0xc0);
const OTHER_COIN = fill(0xc1);
const REGISTRY = fill(0xee);
const ME = fill(1);
const SAVINGS = fill(2);
const STRANGER = fill(9);
const MY_ID = fill(7);
const scope: ScanScope = { chainId: fill(0xaa), registry: REGISTRY, accounts: [ME, SAVINGS], ids: [MY_ID] };

const transfer = (from: Uint8Array, to: Uint8Array, amount: bigint) => new Writer().put(coin.TRANSFER_TOPIC).put(from).put(to).u128(amount).finish();
const payment = (payee: Uint8Array, amount: bigint) => new Writer().put(coin.PRODUCER_PAYMENT_TOPIC).put(payee).u128(amount).finish();
const scheduled = (account: Uint8Array, sequence: bigint) => {
  const w = new Writer().put(coin.SCHEDULED_TOPIC).put(account).u64(sequence).u64(1000).u128(0n);
  coin.encodeEffect(w, { kind: 'transfer', to: STRANGER, amount: 5n });
  return w.finish();
};
const executed = (account: Uint8Array, sequence: bigint) => new Writer().put(coin.EXECUTED_TOPIC).put(account).u64(sequence).finish();
const created = (id: Uint8Array) => new Writer().put(idp.CREATED_TOPIC).put(id).finish();

function block(height: number, events: [Uint8Array, Uint8Array, string?][], id = `b${height}`): BlockEvents {
  return { block: id, height, time: 1_792_000_000 + height * 60, events: events.map(([contract, payload, tx]) => ({ contract, payload, transaction: tx ?? `t${height}` })) };
}

describe('activity scan', () => {
  it('keeps the events that touch the scope, from any coin, and marks fees', () => {
    const items = matchBlocks(
      [
        block(5, [
          [COIN, transfer(STRANGER, ME, 10n)],
          [COIN, transfer(STRANGER, STRANGER, 99n)],
          // A fee: a transfer followed by its producer payment in the same transaction.
          [COIN, transfer(ME, STRANGER, 3n), 'fee'],
          [COIN, payment(STRANGER, 3n), 'fee'],
          // A coin the user does not follow is found too, for discovery.
          [OTHER_COIN, transfer(STRANGER, SAVINGS, 1n)],
          // A zero transfer to itself only carries a bound fee.
          [COIN, transfer(ME, ME, 0n)],
          [REGISTRY, created(MY_ID)],
          [REGISTRY, created(fill(8))],
        ]),
      ],
      scope,
    );
    expect(items.map(([h, , c, , fee]) => [h, c.slice(0, 2), fee])).toEqual([
      [5, 'c0', 0],
      [5, 'c0', 1],
      [5, 'c1', 0],
      [5, 'ee', 0],
    ]);
  });

  it('reads back from the tip first, then forward, without reading a block twice', () => {
    let scan = emptyScan(10);
    expect([scan.low, scan.high]).toEqual([11, 10]);
    scan = addOlder(scan, [block(7, [[COIN, transfer(STRANGER, ME, 1n)]]), block(8, []), block(9, []), block(10, [])], scope);
    expect([scan.low, scan.high, scan.tip]).toEqual([7, 10, 'b10']);
    // The page from `high` overlaps by one block: it is not read again.
    scan = addNewer(scan, [block(10, [[COIN, transfer(STRANGER, ME, 50n)]]), block(11, [[COIN, transfer(ME, STRANGER, 2n)]])], scope);
    expect([scan.low, scan.high, scan.tip]).toEqual([7, 11, 'b11']);
    expect(scan.items.map((it) => it[0])).toEqual([7, 11]);
    scan = addOlder(scan, [block(5, [[COIN, payment(ME, 4n)]]), block(6, [])], scope);
    expect(scan.low).toBe(5);
    expect(activityOf(scan.items, REGISTRY).map((a) => a.height)).toEqual([11, 7, 5]);
  });

  it('forgets the blocks a reorganization may have replaced', () => {
    let scan = emptyScan(40);
    const blocks = Array.from({ length: 40 }, (_, i) => block(i + 1, [[COIN, transfer(STRANGER, ME, BigInt(i + 1))]]));
    scan = addOlder(scan, blocks, scope);
    scan = rollBack(scan, 40 - REORG_DEPTH);
    expect([scan.high, scan.tip, scan.items.length]).toEqual([20, '', 20]);
    // Never below what was read.
    expect(rollBack(addOlder(emptyScan(5), [block(4, []), block(5, [])], scope), 1).high).toBe(3);
  });

  it('saves the newest items, cut at a block boundary', () => {
    let scan = emptyScan(4);
    scan = addOlder(
      scan,
      [
        block(1, [[COIN, transfer(STRANGER, ME, 1n)]]),
        block(2, [[COIN, transfer(STRANGER, ME, 2n)], [COIN, transfer(STRANGER, ME, 3n)]]),
        block(3, [[COIN, transfer(STRANGER, ME, 4n)]]),
        block(4, [[COIN, transfer(STRANGER, ME, 5n)]]),
      ],
      scope,
    );
    // Keeping 3 would split block 2, so all of block 2 goes and the scan starts at 3.
    const kept = trimmed(scan, 3);
    expect([kept.low, kept.items.map((it) => it[0])]).toEqual([3, [3, 4]]);
    expect(trimmed(scan, 10)).toBe(scan);
  });

  it('marks a scheduled action that a later block ran', () => {
    const scan = addOlder(
      emptyScan(3),
      [block(1, [[COIN, scheduled(SAVINGS, 0n)]]), block(2, [[COIN, scheduled(SAVINGS, 1n)]]), block(3, [[COIN, executed(SAVINGS, 0n)]])],
      scope,
    );
    const activity = activityOf(scan.items, REGISTRY);
    expect(activity.map((a) => [a.height, a.coin?.kind, a.settled])).toEqual([
      [3, 'executed', undefined],
      [2, 'scheduled', undefined],
      [1, 'scheduled', 'executed'],
    ]);
  });

  it('joins a fee to the event it paid for, and keeps one paid for nothing shown', () => {
    const items = matchBlocks(
      [
        block(4, [
          [COIN, transfer(ME, STRANGER, 25n), 'pay'],
          [COIN, transfer(ME, fill(5), 1n), 'pay'],
          [COIN, payment(fill(5), 1n), 'pay'],
        ]),
        block(5, [
          [OTHER_COIN, transfer(SAVINGS, fill(5), 2n), 'alone'],
          [OTHER_COIN, payment(fill(5), 2n), 'alone'],
        ]),
      ],
      scope,
    );
    const activity = activityOf(items, REGISTRY);
    expect(activity.map((a) => [a.transaction, a.fee ?? false, a.fees?.map((f) => f.amount)])).toEqual([
      ['alone', true, undefined],
      ['pay', false, [1n]],
    ]);
  });

  it('keeps the time of each block, and reads again a scan saved without times', () => {
    const items = matchBlocks([block(8, [[COIN, transfer(STRANGER, ME, 1n)]]), block(9, [[COIN, transfer(STRANGER, ME, 2n)]])], scope);
    expect(activityOf(items, REGISTRY).map((a) => [a.height, a.time])).toEqual([
      [9, 1_792_000_540],
      [8, 1_792_000_480],
    ]);
    expect(isSavedScan(emptyScan(9))).toBe(true);
    expect(isSavedScan({ ...emptyScan(9), v: 1 })).toBe(false);
  });

  it('names one scan per user and chain, whatever the order of accounts', () => {
    expect(scopeKey(scope)).toBe(scopeKey({ ...scope, accounts: [SAVINGS, ME] }));
    expect(scopeKey(scope)).not.toBe(scopeKey({ ...scope, chainId: fill(0xab) }));
    expect(scopeKey(scope).startsWith(hex(scope.chainId))).toBe(true);
  });
});
