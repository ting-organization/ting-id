// Coins the app follows by default, per chain. There is no on-chain index of
// standard-coin deployments, so the ones a wallet should open on are listed
// here; users add others from Settings, and the app also notices standard
// coins their accounts already hold.
export interface KnownCoin {
  contract: string;
  name: string;
  /** Its mark (ui/coinIcons.tsx); without one, the wallet shows the symbol in a ring. */
  icon?: CoinIconName;
}

/** The marks the app draws for listed coins. */
export type CoinIconName = 'daler';

export const KNOWN_COINS: Record<string, KnownCoin[]> = {
  // The production network.
  '26520bbe40340aabafdc0da89c128e4849146d4b3e59edabd7899d6782a6e653': [
    { contract: 'fed4bce86a8d48c0d854b040454d5c488837fbef890b676406db4a42fc64ad69', name: 'Daler', icon: 'daler' },
  ],
  // The development chain (`ting init --dev`).
  '8578787146be1ef0aacc797754001290272915e89cd9e5784fe1368e4c2131bc': [
    { contract: 'a46fb74968065a4835d99b4ea5cd3e8262b0dec13b84a46a1faccd3d48acfc3a', name: 'Daler', icon: 'daler' },
  ],
};

export function knownCoinsFor(chainHex: string): KnownCoin[] {
  return KNOWN_COINS[chainHex] ?? [];
}
