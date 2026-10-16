import { Platform, TextStyle, useColorScheme } from 'react-native';
import { useStore } from '@/state/store';

export const spacing = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const radius = { sm: 10, md: 12, lg: 16, xl: 24, pill: 999 } as const;

/**
 * The app's look: a dark ink header over a light page, white cards with a
 * hairline, ink buttons, and indigo for links and highlights.
 */
export interface Palette {
  dark: boolean;
  background: string;
  card: string;
  cardAlt: string;
  text: string;
  muted: string;
  faint: string;
  border: string;
  /** Links, selection and highlights. */
  primary: string;
  onPrimary: string;
  primarySoft: string;
  /** The main buttons. */
  ink: string;
  onInk: string;
  /** The dark band at the top of the wallet, an account and the identity. */
  header: string;
  headerRaised: string;
  onHeader: string;
  onHeaderMuted: string;
  accent: string;
  accentSoft: string;
  danger: string;
  dangerSoft: string;
  dangerLine: string;
  warning: string;
  warningSoft: string;
  warningLine: string;
  success: string;
  successSoft: string;
}

export const light: Palette = {
  dark: false,
  background: '#F4F5F7',
  card: '#FFFFFF',
  cardAlt: '#F1F3F6',
  text: '#0B1220',
  muted: '#64748B',
  faint: '#94A3B8',
  border: '#E2E8F0',
  primary: '#4F46E5',
  onPrimary: '#FFFFFF',
  primarySoft: '#EEF2FF',
  ink: '#0B1220',
  onInk: '#FFFFFF',
  header: '#0B1220',
  headerRaised: '#1B2540',
  onHeader: '#F8FAFC',
  onHeaderMuted: '#94A3B8',
  accent: '#0F766E',
  accentSoft: '#E6F6F4',
  danger: '#B91C1C',
  dangerSoft: '#FEF2F2',
  dangerLine: '#FCA5A5',
  warning: '#92400E',
  warningSoft: '#FFFBEB',
  warningLine: '#FDE68A',
  success: '#047857',
  successSoft: '#ECFDF5',
};

export const dark: Palette = {
  dark: true,
  background: '#0A0E16',
  card: '#121826',
  cardAlt: '#182033',
  text: '#F1F5F9',
  muted: '#94A3B8',
  faint: '#64748B',
  border: '#232C3F',
  primary: '#A5B4FC',
  onPrimary: '#0B1220',
  primarySoft: '#1E2547',
  ink: '#F8FAFC',
  onInk: '#0B1220',
  header: '#141C2E',
  headerRaised: '#212B44',
  onHeader: '#F8FAFC',
  onHeaderMuted: '#94A3B8',
  accent: '#2DD4BF',
  accentSoft: '#10302D',
  danger: '#F87171',
  dangerSoft: '#2A1518',
  dangerLine: '#7F2A2E',
  warning: '#FBBF24',
  warningSoft: '#221B0C',
  warningLine: '#5C4615',
  success: '#34D399',
  successSoft: '#0F2E25',
};

/** The palette in use: the one chosen in Settings, else the system's. */
export function useTheme(): Palette {
  const system = useColorScheme();
  const choice = useStore((s) => s.settings.theme);
  return choice === 'dark' || (choice !== 'light' && system === 'dark') ? dark : light;
}

/** Manrope, one family per weight (loaded in the root layout). */
export const fonts = {
  regular: 'Manrope_400Regular',
  medium: 'Manrope_500Medium',
  semibold: 'Manrope_600SemiBold',
  bold: 'Manrope_700Bold',
  extrabold: 'Manrope_800ExtraBold',
} as const;

/** A weight of the app's font, to spread into a text style. */
export const font = {
  regular: { fontFamily: fonts.regular },
  medium: { fontFamily: fonts.medium },
  semibold: { fontFamily: fonts.semibold },
  bold: { fontFamily: fonts.bold },
  extrabold: { fontFamily: fonts.extrabold },
} satisfies Record<string, TextStyle>;

/** Figures that line up in columns. */
export const tabular: TextStyle = { fontVariant: ['tabular-nums'] };

export const type = {
  title: { fontSize: 26, fontFamily: fonts.extrabold, letterSpacing: -0.5 },
  heading: { fontSize: 19, fontFamily: fonts.extrabold, letterSpacing: -0.3 },
  subheading: { fontSize: 15, fontFamily: fonts.bold },
  body: { fontSize: 15, fontFamily: fonts.regular },
  caption: { fontSize: 12.5, fontFamily: fonts.medium },
  // iOS has no font named monospace.
  mono: { fontSize: 13, fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }) },
  amount: { fontSize: 40, fontFamily: fonts.extrabold, letterSpacing: -1, fontVariant: ['tabular-nums'] as TextStyle['fontVariant'] },
} satisfies Record<string, TextStyle>;
