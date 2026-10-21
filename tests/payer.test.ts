// Who pays an operation's fee by default (state/payer).
import { describe, expect, it } from 'vitest';
import { PayerCandidate, defaultPayer } from '../src/state/payer';

const DLR = 'dlr';
const ABC = 'abc';
const option = (slot: number, coin: string, available: bigint | null): PayerCandidate => ({ slot, coin, available });
const base = { feeCoin: DLR, actingCoin: ABC, actingSlot: 0, spendingSlot: 0, usual: 10n };

describe('the default fee payer', () => {
  it('is the acting account in the fee coin when it holds the usual fee', () => {
    const options = [option(0, DLR, 50n), option(0, ABC, 5n)];
    expect(defaultPayer(options, base)).toEqual({ index: 0, short: false });
  });

  it('counts a balance not read yet as enough', () => {
    expect(defaultPayer([option(0, ABC, null), option(0, DLR, null)], base)).toEqual({ index: 1, short: false });
  });

  it('takes Spending for a delayed or other account, before the other instant accounts', () => {
    const options = [option(2, DLR, 50n), option(0, DLR, 50n)];
    expect(defaultPayer(options, { ...base, actingSlot: null }).index).toBe(1);
    expect(defaultPayer(options, { ...base, actingSlot: 2 }).index).toBe(0);
  });

  it('passes over an account that holds too little', () => {
    const options = [option(0, DLR, 5n), option(2, DLR, 50n)];
    expect(defaultPayer(options, base)).toEqual({ index: 1, short: false });
  });

  it('falls back to the coin the operation moves when no account holds the usual fee', () => {
    const options = [option(0, DLR, 5n), option(0, ABC, 100n)];
    expect(defaultPayer(options, base)).toEqual({ index: 1, short: true });
  });

  it('keeps the fee coin when the operation moves no coin of its own', () => {
    const options = [option(0, DLR, 5n)];
    expect(defaultPayer(options, { ...base, actingCoin: null })).toEqual({ index: 0, short: true });
  });

  it('is never short without a usual fee', () => {
    expect(defaultPayer([option(0, DLR, 0n)], { ...base, usual: 0n })).toEqual({ index: 0, short: false });
  });

  it('is none without an instant account', () => {
    expect(defaultPayer([], base)).toEqual({ index: -1, short: true });
  });
});
