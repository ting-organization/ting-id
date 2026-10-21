// History rows name the other account, show its address beside a name, and say what a scheduled action did.
import { describe, expect, it } from 'vitest';
import { hex } from '../src/chain/bytes';
import type { CoinInfo } from '../src/state/coins';
import { describeActivity, groupMinerPayments } from '../src/ui/describe';

const me = new Uint8Array(32).fill(0x11);
const bob = new Uint8Array(32).fill(0x22);
const stranger = new Uint8Array(32).fill(0x33);
const contract = new Uint8Array(32).fill(0x44);
const daler = { hex: hex(contract), id: contract, config: { name: 'Daler', symbol: 'DLR', decimals: 4 } } as unknown as CoinInfo;
const coins = new Map([[daler.hex, daler]]);
const names = new Map([[hex(bob), 'Bob']]);
const sent = (to: Uint8Array) => describeActivity({ height: 1, time: 0, transaction: '01', contract, coin: { kind: 'transfer', from: me, to, amount: 50_000n } }, coins, names, [me]);

describe('a payment in the history', () => {
  it('shows the address beside a known name', () => {
    const d = sent(bob);
    expect(d.party?.name).toBe('Bob');
    expect(d.short).toBe('Sent · 222222…2222');
    expect(d.detail).toBe('to Bob · 222222…2222');
    expect(d.amount).toBe('−5 DLR');
  });

  it('names an unknown account by its address alone', () => {
    const d = sent(stranger);
    expect(d.party?.name).toBe('33333333…333333');
    expect(d.short).toBe('Sent');
  });
});

describe('a miner in the history', () => {
  const pay = (time: number, amount: bigint, transaction: string) => ({ height: time, time, transaction, contract, coin: { kind: 'producerPayment' as const, payee: me, amount } });
  const day = (time: number) => String(Math.floor(time / 86_400));

  it('gets one row per day for its payments, their amounts summed', () => {
    const rows = groupMinerPayments([pay(86_400 * 2 + 50, 30_000n, 'c'), pay(86_400 * 2 + 10, 20_000n, 'b'), pay(86_400 + 10, 10_000n, 'a')], day);
    expect(rows).toHaveLength(2);
    expect(rows[0].count).toBe(2);
    const d = describeActivity(rows[0], coins, names, [me]);
    expect(d.title).toBe('Mining fees');
    expect(d.amount).toBe('+5 DLR');
    expect(d.detail).toContain('2 payments');
    expect(rows[1].count).toBeUndefined();
  });
});

describe('a scheduled action that ran', () => {
  it('says what it did', () => {
    const d = describeActivity(
      { height: 9, time: 0, transaction: '09', contract, coin: { kind: 'executed', account: me, sequence: 3n }, plan: { kind: 'transfer', to: bob, amount: 50_000n } },
      coins,
      names,
      [me],
    );
    expect(d.detail).toBe('Send 5 DLR to Bob');
  });
});
