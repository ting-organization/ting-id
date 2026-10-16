// Typing a recovery phrase into its fields, one word each.
import { describe, expect, it } from 'vitest';
import { enterWords, entryProblem, finishedWord, phraseFields } from '../src/ui/phraseEntry';

const twelve = phraseFields(12);
const valid = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about'.split(' ');

describe('typing a recovery phrase', () => {
  it('moves on after a space, or once no longer word starts with the typed one', () => {
    expect(enterWords(twelve, 0, 'aban').move).toBeNull();
    expect(enterWords(twelve, 0, 'abandon').move).toBe('next');
    // "act" is a word, but "action", "actor" and "actress" start with it.
    expect(finishedWord('act')).toBe(false);
    expect(enterWords(twelve, 0, 'act').move).toBeNull();
    expect(enterWords(twelve, 0, 'act ')).toEqual({ words: ['act', ...twelve.slice(1)], move: 'next' });
    // A space alone, as after a word that moved on by itself, leaves the field empty.
    expect(enterWords(twelve, 1, ' ')).toEqual({ words: twelve, move: null });
  });

  it('takes words in lower case', () => {
    expect(enterWords(twelve, 3, 'Zoo').words[3]).toBe('zoo');
  });

  it('spreads a pasted phrase over the fields, and its length decides how many', () => {
    const pasted = enterWords(twelve, 0, ` ${Array(23).fill('abandon').join(' ')}\nart `);
    expect(pasted.move).toBe('close');
    expect(pasted.words).toHaveLength(24);
    expect(pasted.words[23]).toBe('art');
    expect(enterWords(phraseFields(24), 0, valid.join(' ')).words).toEqual(valid);
    // Past the last field, the next length that holds them.
    const late = enterWords(twelve, 10, 'one two three');
    expect(late.words).toHaveLength(15);
    expect(late.words.slice(10, 13)).toEqual(['one', 'two', 'three']);
  });

  it('names a wrong word by its place, but not while it is typed', () => {
    const words = [...valid.slice(0, 11), 'abou'];
    expect(entryProblem(words, 11)).toBeNull();
    expect(entryProblem(words, null)).toBe('word 12 is not one of the recovery phrase words');
    expect(entryProblem(phraseFields(12, ['abandon']), null)).toBeNull();
    expect(entryProblem(Array(12).fill('abandon'), null)).toMatch(/do not check out/);
    expect(entryProblem(valid, null)).toBeNull();
  });
});
