// Recovery phrases: BIP-39 words, checked, and the key Ting's path gives.
import { describe, expect, it } from 'vitest';
import { HDKey } from '@scure/bip32';
import { mnemonicToSeedSync } from '@scure/bip39';
import { hex } from '../src/chain/bytes';
import { publicKey } from '../src/chain/keys';
import { KEY_PATH, newPhrase, normalizePhrase, phraseProblem, phraseSecret, phraseWords, suggestWords } from '../src/chain/phrase';

const ABANDON = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';

describe('recovery phrases', () => {
  it('give the key the published BIP-39 and BIP-44 vectors give', async () => {
    // BIP-44's own vector for this phrase, through the same libraries: m/44'/0'/0'/0/0.
    const bitcoin = HDKey.fromMasterSeed(mnemonicToSeedSync(ABANDON)).derive("m/44'/0'/0'/0/0");
    expect(hex(bitcoin.publicKey!)).toBe('03aaeb52dd7494c361049de67cc680e83ebcbbbdbeb13637d92cd845f70308af5e');
    // Ting's path, pinned so it never changes under a phrase someone wrote down.
    expect(KEY_PATH).toBe("m/44'/1414090311'/0'/0/0");
    const secret = await phraseSecret(ABANDON);
    expect(hex(publicKey('secp256k1', secret))).toBe(
      '047388c4cccd1893555407977f70e303c242c3d3d6f992ea01583af171a2c02c67136ba4103689143e9d458d7891d2c0c1ea98db313a9aa72ea76bcbbb6ebde46b',
    );
    // However it was typed.
    expect(hex(await phraseSecret(`  ${ABANDON.toUpperCase().replace(/ /g, '  ')} `))).toBe(hex(secret));
  });

  it('are twelve words from sixteen random bytes', () => {
    const words = phraseWords(newPhrase((n) => new Uint8Array(n).fill(7)));
    expect(words).toHaveLength(12);
    expect(phraseProblem(words.join(' '))).toBeNull();
  });

  it('are refused with a reason that names no word', () => {
    expect(phraseProblem('abandon abandon')).toMatch(/12 to 24 words; this has 2/);
    expect(phraseProblem(ABANDON.replace('about', 'aboutx'))).toBe('word 12 is not one of the recovery phrase words');
    expect(phraseProblem(ABANDON.replace('about', 'abandon'))).toMatch(/do not check out/);
    expect(phraseProblem(ABANDON)).toBeNull();
  });

  it('suggest words while typing', () => {
    expect(suggestWords('aba')).toEqual(['abandon']);
    expect(suggestWords('zo')).toEqual(['zone', 'zoo']);
    expect(suggestWords(' ')).toEqual([]);
    expect(normalizePhrase(' Zoo  ZOO\nzoo ')).toBe('zoo zoo zoo');
  });
});
