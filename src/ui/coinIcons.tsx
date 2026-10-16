// Marks of the listed coins (config/known-coins.ts), drawn rather than
// bundled as images so they stay sharp at every size.
import React from 'react';
import Svg, { Circle, Path } from 'react-native-svg';
import { CoinIconName, KNOWN_COINS } from '@/config/known-coins';
import { useTheme } from './theme';

const ICONS = new Map<string, CoinIconName>(
  Object.values(KNOWN_COINS)
    .flat()
    .flatMap((k) => (k.icon ? [[k.contract, k.icon] as const] : [])),
);

/** The mark of a listed coin, by its contract (hex). */
export function coinIconOf(contract: string): CoinIconName | null {
  return ICONS.get(contract) ?? null;
}

export function CoinIcon({ name, size }: { name: CoinIconName; size: number }) {
  switch (name) {
    case 'daler':
      return <Daler size={size} />;
  }
}

const GOLD = '#E3B341';

/** Daler: a gold D with a bar through its stem, in a gold ring on ink. */
function Daler({ size }: { size: number }) {
  const t = useTheme();
  return (
    <Svg width={size} height={size} viewBox="0 0 64 64" accessibilityLabel="Daler">
      {/* On a dark page a hairline keeps the coin's edge in sight. */}
      <Circle cx={32} cy={32} r={t.dark ? 31.5 : 32} fill="#0B1220" stroke={t.dark ? '#334155' : 'none'} strokeWidth={1} />
      <Circle cx={32} cy={32} r={26.5} fill="none" stroke={GOLD} strokeWidth={2.5} />
      <Path d="M24 19H30C38 19 43.5 24.6 43.5 32S38 45 30 45H24Z" fill="none" stroke={GOLD} strokeWidth={5} strokeLinejoin="round" />
      <Path d="M18.5 32H30" stroke={GOLD} strokeWidth={4} strokeLinecap="round" />
    </Svg>
  );
}
