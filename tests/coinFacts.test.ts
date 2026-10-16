// Telling an original coin from its copies (ui/coinFacts): the same ticker
// and the same or a very similar name, the listed or earliest coin first.
import { describe, expect, it } from 'vitest';
import { UNCAPPED, ZERO_ACCOUNT } from '../src/chain/coin';
import type { CoinInfo } from '../src/state/coins';
import { originalOf, reading, similarNames } from '../src/ui/coinFacts';

let next = 0;
function coinInfo(name: string, symbol: string, deployHeight: number): CoinInfo {
  const hex = (++next).toString(16).padStart(64, '0');
  return {
    id: new Uint8Array(32),
    hex,
    config: { name, symbol, decimals: 8, maxSupply: UNCAPPED, initialReward: 0n, halvingInterval: 0n, tailReward: 0n, deployHeight: BigInt(deployHeight) },
    state: { issued: 0n, burned: 0n, lastClaimed: 0n, minter: ZERO_ACCOUNT },
  };
}

describe('copies of a coin', () => {
  it('read names and tickers as people see them', () => {
    expect(reading(' D-L R ')).toBe('dlr');
    expect(reading('DAL0R')).toBe(reading('Dalor'));
    expect(reading('Dalér')).toBe('daler');
  });

  it('count close names as similar, other names not', () => {
    expect(similarNames('Daler', 'daler')).toBe(true);
    expect(similarNames('Daler', 'Dalers')).toBe(true);
    expect(similarNames('Daler', 'Daler Token')).toBe(true);
    expect(similarNames('Daler', 'Dalor')).toBe(true);
    expect(similarNames('Daler', 'Garden')).toBe(false);
    expect(similarNames('Ting Daler', 'Daler')).toBe(true);
    expect(similarNames('Dollar', 'Daler')).toBe(false);
    expect(similarNames('Apple', 'Maple')).toBe(false);
    expect(similarNames('A', 'Garden')).toBe(false);
  });

  it('flag a later coin with the same ticker and a similar name', () => {
    const daler = coinInfo('Daler', 'DLR', 51);
    const copy = coinInfo('Daler Token', 'dlr', 90);
    const lookalike = coinInfo('Da1er', 'D1R', 95);
    const all = [daler, copy, lookalike];
    expect(originalOf(daler, all, [])).toBeNull();
    expect(originalOf(copy, all, [])).toBe(daler);
    expect(originalOf(lookalike, all, [])).toBe(daler);
  });

  it('leave a coin alone when only the ticker or only the name matches', () => {
    const daler = coinInfo('Daler', 'DLR', 51);
    const sameTicker = coinInfo('Delivery Rewards', 'DLR', 60);
    const sameName = coinInfo('Daler', 'DAL', 61);
    const all = [daler, sameTicker, sameName];
    expect(originalOf(sameTicker, all, [])).toBeNull();
    expect(originalOf(sameName, all, [])).toBeNull();
  });

  it('take the listed coin as the original, even when a copy came first', () => {
    const early = coinInfo('Daler', 'DLR', 10);
    const listed = coinInfo('Daler', 'DLR', 51);
    const all = [early, listed];
    expect(originalOf(listed, all, [listed.hex])).toBeNull();
    expect(originalOf(early, all, [listed.hex])).toBe(listed);
  });

  it('take the first deployed within one block', () => {
    const a = coinInfo('Garden', 'GRDN', 5);
    const b = coinInfo('Garden', 'GRDN', 5);
    expect(originalOf(b, [a, b], [])).toBe(a);
    expect(originalOf(a, [a, b], [])).toBeNull();
  });
});
