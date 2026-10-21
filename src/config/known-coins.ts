// Coins the app follows by default, per chain. There is no on-chain index of
// standard-coin deployments, so the ones a wallet should open on are listed
// here; users add others from Settings, and the app also notices standard
// coins their accounts already hold.
export interface KnownCoin {
  contract: string;
  name: string;
  /** What its configuration must say: a node that gives other decimals or another symbol is not believed. */
  decimals?: number;
  symbol?: string;
  /** Its mark (ui/coinIcons.tsx); without one, the wallet shows the symbol in a ring. */
  icon?: CoinIconName;
}

/** The marks the app draws for listed coins. */
export type CoinIconName = 'daler';

export const KNOWN_COINS: Record<string, KnownCoin[]> = {
  // The production network.
  '15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107': [
    { contract: '305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4', name: 'Daler', icon: 'daler', decimals: 8, symbol: 'DLR' },
  ],
  // The development chain (`ting init --dev`).
  '8578787146be1ef0aacc797754001290272915e89cd9e5784fe1368e4c2131bc': [
    { contract: 'a46fb74968065a4835d99b4ea5cd3e8262b0dec13b84a46a1faccd3d48acfc3a', name: 'Daler', icon: 'daler' },
  ],
};

/** The listed coin with contract `coinHex`, on any chain. */
export function knownCoin(coinHex: string): KnownCoin | null {
  for (const coins of Object.values(KNOWN_COINS)) for (const k of coins) if (k.contract === coinHex) return k;
  return null;
}

export function knownCoinsFor(chainHex: string): KnownCoin[] {
  return KNOWN_COINS[chainHex] ?? [];
}
