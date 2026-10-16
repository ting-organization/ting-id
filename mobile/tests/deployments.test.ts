// The coin index's saved state (state/deployments).
import { describe, expect, it } from 'vitest';
import type { BlockReceipts } from '../src/chain/node';
import { advance, candidates, emptyIndex, isSavedIndex, rewind } from '../src/state/deployments';

const id = (n: number) => new Uint8Array(32).fill(n);
const block = (height: number, receipts: BlockReceipts['receipts'] = []): BlockReceipts => ({ id: `b${height}`, height, receipts });

describe('the coin index', () => {
  it('takes the 32-byte results of successful transactions as candidates', () => {
    const blocks = [
      block(1, [
        { status: 0, result: id(1) },
        { status: 7, result: id(2) },
        { status: 0, result: new Uint8Array(0) },
      ]),
      block(2, [{ status: 0, result: id(3) }]),
    ];
    expect(candidates(blocks)).toEqual(['01'.repeat(32), '03'.repeat(32)]);
  });

  it('moves past the blocks read and keeps each coin once', () => {
    let s = advance(emptyIndex(), [block(1), block(2)], ['aa']);
    expect(s).toEqual({ version: 1, next: 3, last: { height: 2, id: 'b2' }, coins: ['aa'] });
    s = advance(s, [block(3)], ['aa', 'bb']);
    expect(s.coins).toEqual(['aa', 'bb']);
    expect(advance(s, [], ['cc'])).toBe(s);
  });

  it('reads again below a replaced block, keeping what it found', () => {
    const s = advance(emptyIndex(), [block(50)], ['aa']);
    expect(rewind(s, 20)).toEqual({ version: 1, next: 31, last: null, coins: ['aa'] });
    expect(rewind(s, 20, 40).next).toBe(21);
    expect(rewind(advance(emptyIndex(), [block(5)], []), 20).next).toBe(1);
  });

  it('accepts only a saved index of its own shape', () => {
    expect(isSavedIndex(emptyIndex())).toBe(true);
    expect(isSavedIndex({ version: 1, next: 0, last: null, coins: [] })).toBe(false);
    expect(isSavedIndex({ version: 2, next: 1, last: null, coins: [] })).toBe(false);
    expect(isSavedIndex(null)).toBe(false);
  });
});
