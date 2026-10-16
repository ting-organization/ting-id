// Building blocks shared by every screen.
import React, { ReactNode, useCallback, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleProp,
  StyleSheet,
  Text,
  TextInput,
  TextInputProps,
  TextStyle,
  View,
  ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as Clipboard from 'expo-clipboard';
import { useFocusEffect } from 'expo-router';
import { setStatusBarStyle } from 'expo-status-bar';
import Ionicons from '@expo/vector-icons/Ionicons';
import Svg, { Circle, Rect } from 'react-native-svg';
import { Palette, font, radius, spacing, tabular, type, useTheme } from './theme';
import { shortId } from '@/chain/format';
import { useChainInfo } from '@/state/chain';
import { nodeNotice } from './describe';

export type IconName = React.ComponentProps<typeof Ionicons>['name'];

const MAX_WIDTH = 680;

/**
 * A screen: an optional dark `header` band across the top, then the content.
 * `title` puts a large title above the content instead.
 */
export function Screen({
  children,
  title,
  right,
  header,
  scroll = true,
  padded = true,
  belowHeader,
  onEndReached,
}: {
  children: ReactNode;
  title?: string;
  right?: ReactNode;
  header?: ReactNode;
  scroll?: boolean;
  padded?: boolean;
  /** Under a native header, which keeps clear of the status bar itself. */
  belowHeader?: boolean;
  /** Called when the screen is scrolled near its end, to show more of a long list. */
  onEndReached?: () => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const body = (
    <View
      style={{
        padding: padded ? spacing.lg : 0,
        paddingTop: padded ? (header ? spacing.lg + 2 : spacing.lg) : 0,
        paddingBottom: spacing.xxl,
        gap: spacing.md,
        maxWidth: MAX_WIDTH,
        width: '100%',
        alignSelf: 'center',
      }}>
      <NodeNotice />
      {title ? (
        <View style={styles.titleRow}>
          <Text style={[type.title, { color: t.text, flex: 1 }]}>{title}</Text>
          {right}
        </View>
      ) : null}
      {children}
    </View>
  );
  const content = (
    <>
      {header}
      {body}
    </>
  );
  return (
    <View style={{ flex: 1, backgroundColor: t.background, paddingTop: header || belowHeader || Platform.OS === 'web' ? 0 : insets.top }}>
      {scroll ? (
        <ScrollView
          contentContainerStyle={{ flexGrow: 1 }}
          keyboardShouldPersistTaps="handled"
          scrollEventThrottle={200}
          onScroll={
            onEndReached
              ? ({ nativeEvent: { layoutMeasurement, contentOffset, contentSize } }) => {
                  if (layoutMeasurement.height + contentOffset.y >= contentSize.height - 240) onEndReached();
                }
              : undefined
          }>
          {content}
        </ScrollView>
      ) : (
        content
      )}
    </View>
  );
}

/** The one message on every screen about the nodes: none answers, or their answers did not check out. */
function NodeNotice() {
  const { info, error, notice } = useChainInfo();
  const message = nodeNotice(info?.status.height ?? null, error, notice);
  return message ? (
    <Banner tone={notice?.kind === 'distrusted' && !error ? 'danger' : 'warning'} icon={error ? 'cloud-offline-outline' : 'shield-outline'}>
      {message}
    </Banner>
  ) : null;
}

/** The dark band at the top of a screen: rounded at the bottom, under the status bar, which turns light while it shows. */
export function InkHeader({ children }: { children: ReactNode }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  useFocusEffect(
    useCallback(() => {
      setStatusBarStyle('light');
      return () => setStatusBarStyle(t.dark ? 'light' : 'dark');
    }, [t.dark]),
  );
  return (
    <View
      style={{
        backgroundColor: t.header,
        borderBottomLeftRadius: radius.xl,
        borderBottomRightRadius: radius.xl,
        paddingTop: (Platform.OS === 'web' ? 0 : insets.top) + 16,
        paddingBottom: 22,
        paddingHorizontal: 20,
      }}>
      <View style={{ maxWidth: MAX_WIDTH - 8, width: '100%', alignSelf: 'center', gap: 18 }}>{children}</View>
    </View>
  );
}

/** The Ting ID mark: a T in a ring on indigo. */
export function Logo({ size = 24 }: { size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 1024 1024" accessibilityLabel="Ting ID">
      <Rect width={1024} height={1024} rx={224} fill="#4F46E5" />
      <Circle cx={512} cy={512} r={300} fill="none" stroke="#FFFFFF" strokeWidth={76} />
      <Rect x={382} y={368} width={260} height={84} rx={42} fill="#FFFFFF" />
      <Rect x={470} y={476} width={84} height={196} rx={42} fill="#FFFFFF" />
    </Svg>
  );
}

/** `tone`: `alt` a quieter fill, `warning` the amber of something that needs a look. */
export function Card({ children, style, tone, padded = true }: { children: ReactNode; style?: StyleProp<ViewStyle>; tone?: 'alt' | 'warning'; padded?: boolean }) {
  const t = useTheme();
  const colors = tone === 'warning' ? { bg: t.warningSoft, border: t.warningLine } : tone === 'alt' ? { bg: t.cardAlt, border: t.border } : { bg: t.card, border: t.border };
  return (
    <View
      style={[
        {
          backgroundColor: colors.bg,
          borderRadius: radius.lg,
          padding: padded ? spacing.lg : 0,
          borderWidth: 1,
          borderColor: colors.border,
          gap: spacing.sm,
          overflow: 'hidden',
        },
        tone ? null : shadow(t),
        style,
      ]}>
      {children}
    </View>
  );
}

function shadow(t: Palette): ViewStyle {
  if (t.dark) return {};
  return Platform.select<ViewStyle>({
    web: { boxShadow: '0 1px 2px rgba(15, 23, 42, 0.04)' } as ViewStyle,
    default: { shadowColor: '#0F172A', shadowOpacity: 0.04, shadowRadius: 2, shadowOffset: { width: 0, height: 1 }, elevation: 1 },
  })!;
}

export function H({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const t = useTheme();
  return <Text style={[type.heading, { color: t.text }, style]}>{children}</Text>;
}

export function Sub({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const t = useTheme();
  return <Text style={[type.subheading, { color: t.text }, style]}>{children}</Text>;
}

export function Body({ children, muted, style }: { children: ReactNode; muted?: boolean; style?: StyleProp<TextStyle> }) {
  const t = useTheme();
  return <Text style={[type.body, { color: muted ? t.muted : t.text, lineHeight: 22 }, style]}>{children}</Text>;
}

export function Caption({ children, style, numberOfLines }: { children: ReactNode; style?: StyleProp<TextStyle>; numberOfLines?: number }) {
  const t = useTheme();
  return (
    <Text style={[type.caption, { color: t.muted, lineHeight: 18 }, style]} numberOfLines={numberOfLines}>
      {children}
    </Text>
  );
}

export function Mono({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const t = useTheme();
  return (
    <Text style={[type.mono, { color: t.text }, style]} selectable>
      {children}
    </Text>
  );
}

/** An amount: figures in columns. */
export function Amount({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const t = useTheme();
  return <Text style={[{ color: t.text, fontSize: 15, ...font.semibold }, tabular, style]}>{children}</Text>;
}

/** An (i) that explains on tap, in a sheet. `light`: on the dark header. */
export function Info({ title, text, light }: { title: string; text: string; light?: boolean }) {
  const t = useTheme();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Pressable onPress={() => setOpen(true)} hitSlop={10} accessibilityLabel={`About ${title}`}>
        <Ionicons name="information-circle-outline" size={18} color={light ? t.onHeaderMuted : t.faint} />
      </Pressable>
      <Sheet visible={open} onClose={() => setOpen(false)} title={title}>
        <Body muted>{text}</Body>
      </Sheet>
    </>
  );
}

/** Section title with an optional action on the right, like "Manage". */
export function SectionHeader({ title, action, onAction, icon, info }: { title: string; action?: string; onAction?: () => void; icon?: IconName; info?: string }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, marginTop: spacing.xs }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        {icon ? <Ionicons name={icon} size={16} color={t.muted} /> : null}
        <Text style={{ color: t.text, fontSize: 16, ...font.extrabold, letterSpacing: -0.2 }}>{title}</Text>
        {info ? <Info title={title} text={info} /> : null}
      </View>
      {action && onAction ? (
        <Pressable onPress={onAction} hitSlop={8}>
          <Text style={{ color: t.primary, fontSize: 13.5, ...font.bold }}>{action}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

/**
 * `primary` ink, the main action; `secondary` white with a hairline; `ghost` a
 * link; `danger` red on white; `destructive` solid red, for what cannot be
 * undone; `light` and `raised` sit on the dark header.
 */
export type ButtonKind = 'primary' | 'secondary' | 'ghost' | 'danger' | 'destructive' | 'light' | 'raised';

function buttonColors(t: Palette, kind: ButtonKind): { bg: string; fg: string; border?: string } {
  return {
    primary: { bg: t.ink, fg: t.onInk },
    secondary: { bg: t.card, fg: t.text, border: t.border },
    ghost: { bg: 'transparent', fg: t.primary },
    danger: { bg: t.card, fg: t.danger, border: t.dangerLine },
    destructive: { bg: t.danger, fg: '#FFFFFF' },
    light: { bg: '#FFFFFF', fg: '#0B1220' },
    raised: { bg: t.headerRaised, fg: t.onHeader },
  }[kind];
}

export function Button({
  title,
  onPress,
  kind = 'primary',
  disabled,
  loading,
  small,
  large,
  icon,
  style,
}: {
  title: string;
  onPress?: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
  loading?: boolean;
  small?: boolean;
  /** The one big button at the end of a form. */
  large?: boolean;
  icon?: IconName;
  style?: StyleProp<ViewStyle>;
}) {
  const t = useTheme();
  const c = buttonColors(t, kind);
  const link = kind === 'ghost';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      accessibilityRole="button"
      style={({ pressed }) => [
        {
          backgroundColor: c.bg,
          borderRadius: large ? radius.lg : radius.md,
          minHeight: link ? undefined : large ? 56 : small ? 34 : 46,
          paddingVertical: link ? (small ? 4 : 8) : 0,
          paddingHorizontal: link ? 4 : small ? 14 : 18,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: disabled ? 0.4 : pressed ? 0.8 : 1,
          borderWidth: c.border ? 1 : 0,
          borderColor: c.border,
          flexDirection: 'row',
          gap: 8,
          // A small link sits in a row; a full one stands alone, under the button it is the other choice to.
          alignSelf: link ? (small ? 'flex-start' : 'center') : undefined,
        },
        style,
      ]}>
      {loading ? <ActivityIndicator color={c.fg} /> : icon ? <Ionicons name={icon} size={small ? 15 : 17} color={c.fg} /> : null}
      <Text style={{ color: c.fg, fontSize: large ? 16 : small ? 13.5 : 15, ...(large ? font.extrabold : font.bold) }}>{title}</Text>
    </Pressable>
  );
}

/** A square button with only an icon. */
export function IconButton({ icon, label, onPress, kind = 'secondary', size = 44, disabled }: { icon: IconName; label: string; onPress: () => void; kind?: ButtonKind; size?: number; disabled?: boolean }) {
  const t = useTheme();
  const c = buttonColors(t, kind);
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={label}
      hitSlop={4}
      style={({ pressed }) => ({
        width: size,
        height: size,
        borderRadius: radius.md,
        backgroundColor: c.bg,
        borderWidth: c.border ? 1 : 0,
        borderColor: c.border,
        alignItems: 'center',
        justifyContent: 'center',
        opacity: disabled ? 0.4 : pressed ? 0.75 : 1,
      })}>
      <Ionicons name={icon} size={size * 0.42} color={c.fg} />
    </Pressable>
  );
}

export function IconCircle({ name, color, bg, size = 40 }: { name: IconName; color: string; bg: string; size?: number }) {
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: bg, alignItems: 'center', justifyContent: 'center' }}>
      <Ionicons name={name} size={size * 0.48} color={color} />
    </View>
  );
}

/** A count with its label and a short line under it. `onHeader`: in the dark header. */
export function StatTile({ label, value, icon, hint, onHeader }: { label: string; value: string; icon: IconName; hint?: string; onHeader?: boolean }) {
  const t = useTheme();
  const fg = onHeader ? t.onHeader : t.text;
  const muted = onHeader ? t.onHeaderMuted : t.muted;
  return (
    <View style={{ flex: 1, backgroundColor: onHeader ? t.headerRaised : t.cardAlt, borderRadius: radius.md, padding: spacing.md, gap: 4 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
        <Ionicons name={icon} size={15} color={muted} />
        <Text style={{ color: muted, fontSize: 12.5, ...font.semibold }}>{label}</Text>
      </View>
      <Text style={[{ color: fg, fontSize: 22, ...font.extrabold, letterSpacing: -0.4 }, tabular]}>{value}</Text>
      {hint ? (
        <Text style={{ color: muted, fontSize: 12, ...font.medium }} numberOfLines={2}>
          {hint}
        </Text>
      ) : null}
    </View>
  );
}

export function Field({
  label,
  hint,
  error,
  style,
  ...props
}: TextInputProps & { label?: string; hint?: string; error?: string | null }) {
  const t = useTheme();
  return (
    <View style={{ gap: 6 }}>
      {label ? <Text style={{ color: t.muted, fontSize: 12.5, ...font.semibold }}>{label}</Text> : null}
      <TextInput
        placeholderTextColor={t.faint}
        {...props}
        style={[
          {
            backgroundColor: t.cardAlt,
            color: t.text,
            borderRadius: radius.md,
            borderWidth: 1,
            borderColor: error ? t.danger : t.border,
            paddingHorizontal: 14,
            paddingVertical: Platform.OS === 'web' ? 12 : 10,
            fontSize: 16,
            ...font.medium,
          },
          props.multiline ? { minHeight: 80, textAlignVertical: 'top' } : null,
          style,
        ]}
      />
      {error ? <Text style={[type.caption, { color: t.danger }]}>{error}</Text> : hint ? <Caption>{hint}</Caption> : null}
    </View>
  );
}

export type Tone = 'neutral' | 'primary' | 'accent' | 'warning' | 'danger' | 'success';

export function toneColors(t: Palette, tone: Tone): { bg: string; fg: string } {
  return {
    neutral: { bg: t.cardAlt, fg: t.muted },
    primary: { bg: t.primarySoft, fg: t.primary },
    accent: { bg: t.accentSoft, fg: t.accent },
    warning: { bg: t.warningSoft, fg: t.warning },
    danger: { bg: t.dangerSoft, fg: t.danger },
    success: { bg: t.successSoft, fg: t.success },
  }[tone];
}

/** A small label. `light`: on the dark header. */
export function Pill({ label, tone = 'neutral', small, light }: { label: string; tone?: Tone; small?: boolean; light?: boolean }) {
  const t = useTheme();
  const c = light ? (tone === 'warning' ? { bg: 'rgba(251,191,36,0.14)', fg: '#FCD34D' } : { bg: t.headerRaised, fg: '#CBD5E1' }) : toneColors(t, tone);
  return (
    <View style={{ backgroundColor: c.bg, borderRadius: radius.pill, paddingHorizontal: small ? 8 : 10, paddingVertical: small ? 2 : 3, alignSelf: 'flex-start' }}>
      <Text style={{ color: c.fg, fontSize: small ? 11 : 12, ...font.bold }}>{label}</Text>
    </View>
  );
}

export function initialsOf(name: string): string {
  return (
    name
      .split(/[\s·]+/)
      .map((p) => p.replace(/[^\p{L}\p{N}]/gu, '')[0])
      .filter(Boolean)
      .slice(0, 2)
      .join('')
      .toUpperCase() || '?'
  );
}

/** A person or user: initials on their colour, or on a soft tint when `soft`. */
export function Avatar({ name, color, size = 40, soft }: { name: string; color: string; size?: number; soft?: boolean }) {
  const t = useTheme();
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: soft ? t.primarySoft : color,
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <Text style={{ color: soft ? t.primary : '#FFFFFF', fontSize: size * 0.38, ...font.extrabold }}>{initialsOf(name)}</Text>
    </View>
  );
}

/** A 32-byte identifier, shortened, copied on tap. `light`: on the dark header. */
export function IdChip({ value, label, full, light }: { value: string; label?: string; full?: boolean; light?: boolean }) {
  const t = useTheme();
  const [copied, setCopied] = useState(false);
  const fg = light ? t.onHeader : t.text;
  const muted = light ? t.onHeaderMuted : t.muted;
  return (
    <Pressable
      onPress={async () => {
        await Clipboard.setStringAsync(value);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      }}
      accessibilityLabel={`Copy ${label ?? 'id'}`}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
        backgroundColor: light ? t.headerRaised : t.cardAlt,
        borderRadius: radius.pill,
        paddingHorizontal: 10,
        paddingVertical: 5,
        opacity: pressed ? 0.7 : 1,
        alignSelf: 'flex-start',
        maxWidth: '100%',
      })}>
      {label ? <Text style={[type.caption, { color: muted }]}>{label}</Text> : null}
      <Text style={[type.mono, { color: fg, flexShrink: 1 }]} numberOfLines={full ? 3 : 1}>
        {full ? value : shortId(value, 8, 6)}
      </Text>
      <Ionicons name={copied ? 'checkmark' : 'copy-outline'} size={13} color={copied ? (light ? t.onHeader : t.success) : muted} />
    </Pressable>
  );
}

export function Row({ children, style, gap = spacing.sm }: { children: ReactNode; style?: StyleProp<ViewStyle>; gap?: number }) {
  return <View style={[{ flexDirection: 'row', alignItems: 'center', gap }, style]}>{children}</View>;
}

export function ListItem({
  title,
  subtitle,
  left,
  right,
  onPress,
  selected,
  chevron,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  left?: ReactNode;
  right?: ReactNode;
  onPress?: () => void;
  selected?: boolean;
  chevron?: boolean;
}) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityState={selected ? { selected: true } : undefined}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.md,
        paddingVertical: 12,
        paddingHorizontal: spacing.lg,
        backgroundColor: selected ? t.primarySoft : pressed ? t.cardAlt : 'transparent',
      })}>
      {left}
      <View style={{ flex: 1, gap: 2 }}>
        {typeof title === 'string' ? <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }}>{title}</Text> : title}
        {typeof subtitle === 'string' ? <Caption>{subtitle}</Caption> : subtitle}
      </View>
      {right}
      {chevron ? <Ionicons name="chevron-forward" size={16} color={t.faint} /> : null}
    </Pressable>
  );
}

/** A choice of a few: chips side by side, the chosen one in ink. */
export function Segmented<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', gap: spacing.sm }} accessibilityRole="radiogroup">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <Pressable
            key={o.value}
            onPress={() => onChange(o.value)}
            accessibilityRole="radio"
            accessibilityState={{ selected: active }}
            style={{
              flex: 1,
              // At least this tall, taller for large text.
              minHeight: 38,
              paddingVertical: 6,
              borderRadius: radius.md,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: active ? t.ink : t.card,
              borderWidth: active ? 0 : 1,
              borderColor: t.border,
            }}>
            <Text style={{ color: active ? t.onInk : t.text, fontSize: 13.5, textAlign: 'center', ...font.bold }} numberOfLines={2}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Banner({ children, tone = 'info', icon }: { children: ReactNode; tone?: 'info' | 'warning' | 'danger' | 'success'; icon?: IconName }) {
  const t = useTheme();
  const map = {
    info: { bg: t.primarySoft, fg: t.primary, line: t.primarySoft, icon: 'information-circle-outline' as IconName },
    warning: { bg: t.warningSoft, fg: t.warning, line: t.warningLine, icon: 'alert-circle-outline' as IconName },
    danger: { bg: t.dangerSoft, fg: t.danger, line: t.dangerLine, icon: 'close-circle-outline' as IconName },
    success: { bg: t.successSoft, fg: t.success, line: t.successSoft, icon: 'checkmark-circle-outline' as IconName },
  }[tone];
  return (
    <View style={{ backgroundColor: map.bg, borderRadius: radius.md, borderWidth: 1, borderColor: map.line, padding: spacing.md, flexDirection: 'row', gap: 10, alignItems: 'flex-start' }}>
      <Ionicons name={icon ?? map.icon} size={18} color={map.fg} style={{ marginTop: 1 }} />
      <Text style={{ color: map.fg, fontSize: 14, lineHeight: 20, flex: 1, ...font.medium }}>{children}</Text>
    </View>
  );
}

export function Empty({ title, body, icon = 'leaf-outline' }: { title: string; body?: string; icon?: IconName }) {
  const t = useTheme();
  return (
    <View style={{ alignItems: 'center', padding: spacing.xl, gap: 8 }}>
      <IconCircle name={icon} color={t.muted} bg={t.cardAlt} size={48} />
      <Text style={[type.subheading, { color: t.text }]}>{title}</Text>
      {body ? <Caption style={{ textAlign: 'center' }}>{body}</Caption> : null}
    </View>
  );
}

export function Loading({ label }: { label?: string }) {
  const t = useTheme();
  return (
    <View style={{ alignItems: 'center', padding: spacing.xl, gap: spacing.sm }}>
      <ActivityIndicator color={t.muted} />
      {label ? <Caption>{label}</Caption> : null}
    </View>
  );
}

/** A bottom sheet on phones, a centered dialog on wide screens. */
export function Sheet({ visible, onClose, title, children }: { visible: boolean; onClose: () => void; title?: string; children: ReactNode }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      {/* The keyboard pushes the sheet up on iOS, so its fields and buttons stay in sight. */}
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <Pressable onPress={onClose} style={{ flex: 1, backgroundColor: 'rgba(8,12,20,0.55)', justifyContent: 'flex-end' }}>
          <Pressable
            onPress={() => {}}
            style={{
              backgroundColor: t.card,
              borderTopLeftRadius: radius.xl,
              borderTopRightRadius: radius.xl,
              padding: spacing.lg,
              paddingBottom: spacing.lg + insets.bottom,
              gap: spacing.md,
              maxHeight: '85%',
              width: '100%',
              maxWidth: MAX_WIDTH,
              alignSelf: 'center',
            }}>
            <View style={{ width: 36, height: 4, borderRadius: 2, backgroundColor: t.border, alignSelf: 'center' }} />
            {title ? <H>{title}</H> : null}
            <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ gap: spacing.sm }}>
              {children}
            </ScrollView>
          </Pressable>
        </Pressable>
      </KeyboardAvoidingView>
    </Modal>
  );
}

/** Rows of labels and values in a bordered box, such as a coin's details. */
export function DetailCard({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <View style={{ borderWidth: 1, borderColor: t.border, borderRadius: 16, overflow: 'hidden', backgroundColor: t.card }}>{children}</View>;
}

/** A label and its value, one row of a DetailCard; the first has no line above it. */
export function Detail({ label, children, first }: { label: string; children: ReactNode; first?: boolean }) {
  const t = useTheme();
  return (
    <Row
      style={{
        justifyContent: 'space-between',
        paddingHorizontal: spacing.lg,
        paddingVertical: 12,
        borderTopWidth: first ? 0 : 1,
        borderTopColor: t.border,
      }}>
      <Text style={{ color: t.muted, fontSize: 13.5, ...font.semibold }}>{label}</Text>
      {children}
    </Row>
  );
}

/** A value in a Detail row, its figures lined up. */
export function DetailValue({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Text style={[{ color: t.text, fontSize: 13.5, ...font.bold, flexShrink: 1, textAlign: 'right' }, tabular]}>{children}</Text>;
}

export function Divider() {
  const t = useTheme();
  return <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: t.border, marginVertical: spacing.xs }} />;
}

const styles = StyleSheet.create({
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginBottom: spacing.xs, paddingHorizontal: 4 },
});
