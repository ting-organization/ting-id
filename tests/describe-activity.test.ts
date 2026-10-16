// History rows name the other account, and show its address beside a name.
import { describe, expect, it } from 'vitest';
import { hex } from '../src/chain/bytes';
import type { CoinInfo } from '../src/state/coins';
import { describeActivity } from '../src/ui/describe';

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
