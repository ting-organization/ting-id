// What typing into one field of a recovery phrase does: a word, a word and a
// space, or a pasted phrase spread over the fields.
import { PHRASE_LENGTHS, WORDS, phraseProblem, suggestWords } from '@/chain/phrase';

const KNOWN = new Set(WORDS);

/** Whether `word` is one of the 2,048. */
export const isPhraseWord = (word: string) => KNOWN.has(word);

/** Empty fields for a phrase of `length` words, keeping what `words` has. */
export function phraseFields(length: number, words: string[] = []): string[] {
  return Array.from({ length }, (_, i) => words[i] ?? '');
}

/** On the list, and no longer word starts with it: nothing more to type. */
export function finishedWord(word: string): boolean {
  return KNOWN.has(word) && suggestWords(word, 2).length === 1;
}

/**
 * `raw` typed into field `i`: the words then, and whether the next field takes
 * over or the keyboard closes. A pasted phrase fills the fields from `i` on;
 * pasted into the first, its own length decides how many there are.
 */
export function enterWords(words: string[], i: number, raw: string): { words: string[]; move: 'next' | 'close' | null } {
  const parts = raw.toLowerCase().split(/\s+/).filter(Boolean);
  if (parts.length > 1) {
    const length =
      i === 0 && PHRASE_LENGTHS.includes(parts.length) ? parts.length : (PHRASE_LENGTHS.find((n) => n >= Math.max(words.length, i + parts.length)) ?? words.length);
    const next = phraseFields(length, words);
    parts.slice(0, length - i).forEach((p, k) => (next[i + k] = p));
    return { words: next, move: 'close' };
  }
  const word = parts[0] ?? '';
  // A space after the word, or a word that needs nothing more, moves on.
  const move = word && (/\s$/.test(raw) || finishedWord(word)) ? 'next' : null;
  return { words: words.map((w, j) => (j === i ? word : w)), move };
}

/** Why the words are no phrase, leaving alone the one in field `typing`; with all words in, the checksum's say. */
export function entryProblem(words: string[], typing: number | null): string | null {
  const unknown = words.findIndex((w, i) => w && i !== typing && !KNOWN.has(w));
  if (unknown >= 0) return `word ${unknown + 1} is not one of the recovery phrase words`;
  return words.every((w) => KNOWN.has(w)) ? phraseProblem(words.join(' ')) : null;
}
