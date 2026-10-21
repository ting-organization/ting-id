// Recovery phrases (RECOVERY.md): twelve BIP-39 English words for a new
// phrase, any valid 12 to 24 for a restore, and the one secp256k1 key the
// words give, at m/44'/1414090311'/0'/0/0 (BIP-32).
import { HDKey } from '@scure/bip32';
import { entropyToMnemonic, mnemonicToSeed, validateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english.js';

/** The coin type in the key path: "TING" read as a number. */
export const COIN_TYPE = 1414090311;
/** Where the phrase's key is: the first key of the first account. */
export const KEY_PATH = `m/44'/${COIN_TYPE}'/0'/0/0`;
/** Words in a new phrase. */
export const NEW_PHRASE_WORDS = 12;
/** Phrase lengths a restore accepts. */
export const PHRASE_LENGTHS = [12, 15, 18, 21, 24];

/** The 2,048 words a phrase is made of. */
export const WORDS: readonly string[] = wordlist;

/** A new phrase of twelve words, from 16 random bytes. */
export function newPhrase(random: (n: number) => Uint8Array): string {
  return entropyToMnemonic(random(16), wordlist);
}

/** The words as typed: lower case, one space between them. */
export function normalizePhrase(text: string): string {
  return text.trim().toLowerCase().split(/\s+/).filter(Boolean).join(' ');
}

/** The words of a phrase, in order. */
export function phraseWords(text: string): string[] {
  const t = normalizePhrase(text);
  return t ? t.split(' ') : [];
}

/**
 * Why the text is not a recovery phrase, or null. A word is named by its
 * place, never repeated: the words are a secret.
 */
export function phraseProblem(text: string): string | null {
  const words = phraseWords(text);
  if (!PHRASE_LENGTHS.includes(words.length)) return `a recovery phrase has 12 to 24 words; this has ${words.length}`;
  const unknown = words.findIndex((w) => !wordlist.includes(w));
  if (unknown >= 0) return `word ${unknown + 1} is not one of the recovery phrase words`;
  if (!validateMnemonic(words.join(' '), wordlist)) return 'the words do not check out: one of them may be wrong, or out of order';
  return null;
}

/** Words of the list that start with `prefix`, for suggestions while typing. */
export function suggestWords(prefix: string, max = 4): string[] {
  const p = prefix.trim().toLowerCase();
  if (!p) return [];
  const out: string[] = [];
  for (const w of wordlist) {
    if (w.startsWith(p)) out.push(w);
    if (out.length === max) break;
  }
  return out;
}

/** The secret of the phrase's key (32 bytes, secp256k1). Takes a moment: the words are stretched first. */
export async function phraseSecret(phrase: string): Promise<Uint8Array> {
  const seed = await mnemonicToSeed(normalizePhrase(phrase));
  const key = HDKey.fromMasterSeed(seed).derive(KEY_PATH);
  if (!key.privateKey) throw new Error('the phrase gives no key');
  return key.privateKey;
}
