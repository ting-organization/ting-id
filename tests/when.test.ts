// When a payment set for later goes: the chosen local date and time.
import { describe, expect, it } from 'vitest';
import { LATEST, atTime, defaultLater, laterProblem, onDay } from '../src/ui/when';

const local = (y: number, m: number, d: number, h = 0, min = 0) => Math.floor(new Date(y, m - 1, d, h, min).getTime() / 1000);

describe('a payment set for later', () => {
  it('is first proposed for the next full hour a day ahead', () => {
    const now = local(2026, 10, 16, 11, 19);
    expect(defaultLater(now)).toBe(local(2026, 10, 17, 12, 0));
    expect(defaultLater(local(2026, 10, 16, 11, 0))).toBe(local(2026, 10, 17, 11, 0));
  });

  it('keeps its time when the day changes, and its day when the time changes', () => {
    const at = local(2026, 10, 17, 14, 30);
    expect(onDay(at, new Date(2026, 11, 24, 9, 5))).toBe(local(2026, 12, 24, 14, 30));
    expect(atTime(at, new Date(2020, 0, 1, 8, 45, 59))).toBe(local(2026, 10, 17, 8, 45));
  });

  it('must be in the future and within the year the node holds it', () => {
    const now = local(2026, 10, 16, 11, 19);
    expect(laterProblem(now + 30, now)).toBe('choose a time in the future');
    expect(laterProblem(now + 3600, now)).toBeNull();
    expect(laterProblem(now + LATEST, now)).toBeNull();
    expect(laterProblem(now + LATEST + 1, now)).toBe('choose a time within a year');
  });
});
