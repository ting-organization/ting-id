// The amount on Send, grouped while it is typed.
import { describe, expect, it } from 'vitest';
import { parseAmount } from '../src/chain/format';
import { groupTyped, ungroupTyped } from '../src/ui/amountInput';

describe('an amount being typed', () => {
  it('groups the whole part in threes and leaves the decimals as typed', () => {
    expect(groupTyped('')).toBe('');
    expect(groupTyped('999')).toBe('999');
    expect(groupTyped('40000')).toBe('40 000');
    expect(groupTyped('1234567.')).toBe('1 234 567.');
    expect(groupTyped('1234567.12345')).toBe('1 234 567.12345');
    expect(groupTyped('1234,5')).toBe('1 234,5');
    expect(groupTyped('12a')).toBe('12a');
  });

  it('reads back as typed, so the amount parses the same', () => {
    for (const raw of ['5', '40000', '1234567.5', '0.0001']) {
      expect(ungroupTyped(groupTyped(raw))).toBe(raw);
      expect(parseAmount(groupTyped(raw), 4)).toBe(parseAmount(raw, 4));
    }
    // A digit typed after the last group joins the number.
    expect(ungroupTyped(`${groupTyped('40000')}1`)).toBe('400001');
  });
});
