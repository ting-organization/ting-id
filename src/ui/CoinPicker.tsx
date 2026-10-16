// Coins as marks, the wallet's coin cards with the accounts under each, and
// a sheet to pick the account to send from.
import React from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import * as coin from '@/chain/coin';
import { formatAmount } from '@/chain/format';
import type { AccountView, CoinGroup, CoinInfo } from '@/state/coins';
import { coinColor } from '@/state/store';
import { Caption, Card, Sheet } from './components';
import { lockOf } from './describe';
import { font, spacing, tabular, useTheme } from './theme';

/** `#rrggbb` mixed toward white by `amount` (0..1), so a coin's colour reads on dark cards. */
function lighten(hex: string, amount: number): string {
  const n = parseInt(hex.replace('#', ''), 16);
  const f = (c: number) => Math.round(c + (255 - c) * amount);
  return `#${((1 << 24) | (f(n >> 16) << 16) | (f((n >> 8) & 255) << 8) | f(n & 255)).toString(16).slice(1)}`;
}

/** A coin's mark: its symbol in a ring of its colour. */
export function CoinMark({ coin: c, size = 36 }: { coin: CoinInfo; size?: number }) {
  const t = useTheme();
  const color = t.dark ? lighten(coinColor(c.hex), 0.35) : coinColor(c.hex);
  const symbol = (c.config.symbol || c.config.name).slice(0, 4).toUpperCase();
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        borderWidth: 2,
        borderColor: color,
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <Text style={{ color, fontSize: size * (symbol.length > 3 ? 0.22 : 0.27), ...font.extrabold }} numberOfLines={1}>
        {symbol}
      </Text>
    </View>
  );
}

/** What an account is: instant, or how long its withdrawals wait. */
export function accountNote(view: AccountView): string {
  return view.account.delay ? lockOf(view.account.delay) : 'Instant';
}

/** Payments from an account that no block has included yet. */
export interface OnTheWay {
  count: number;
  amount: bigint;
}

/** One account of a coin: its name and lock, what it has available, and what is reserved or on the way. */
function AccountLine({
  coin: c,
  view,
  selected,
  onPress,
  first,
  pending,
  symbol,
}: {
  coin: CoinInfo;
  view: AccountView;
  selected?: boolean;
  onPress: () => void;
  first?: boolean;
  pending?: OnTheWay;
  /** Show the symbol beside the amount. */
  symbol?: boolean;
}) {
  const t = useTheme();
  const reserved = view.record.locked;
  const dec = c.config.decimals;
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
        paddingVertical: 11,
        paddingHorizontal: spacing.lg,
        borderTopWidth: first ? 0 : 1,
        borderTopColor: t.border,
        backgroundColor: selected ? t.primarySoft : pressed ? t.cardAlt : 'transparent',
      })}>
      <View style={{ flex: 1, gap: 1 }}>
        <Text style={{ color: t.text, fontSize: 14, ...font.semibold }}>{view.account.label}</Text>
        <Caption>
          {accountNote(view)}
          {reserved > 0n ? (
            <Text style={{ color: t.warning }}>
              {' · '}
              {formatAmount(reserved, dec)} reserved
            </Text>
          ) : null}
          {pending && pending.count > 0 ? ` · ${formatAmount(pending.amount, dec)} on the way` : ''}
        </Caption>
      </View>
      <Text style={[{ color: t.text, fontSize: 15, ...font.semibold }, tabular]}>
        {formatAmount(coin.available(view.record), dec)}
        {symbol ? ` ${c.config.symbol}` : ''}
      </Text>
      {selected ? <Ionicons name="checkmark" size={18} color={t.primary} /> : <Ionicons name="chevron-forward" size={16} color={t.faint} />}
    </Pressable>
  );
}

/** A coin's heading: its mark and name, and the total of its accounts. */
function CoinHead({ group, total }: { group: CoinGroup; total: boolean }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: spacing.lg, paddingVertical: 14 }}>
      <CoinMark coin={group.coin} />
      <View style={{ flex: 1 }}>
        <Text style={{ color: t.text, fontSize: 15, ...font.bold }} numberOfLines={1}>
          {group.coin.config.name}
        </Text>
        {total ? null : <Caption>{group.coin.config.symbol}</Caption>}
      </View>
      {total ? (
        <Text style={[{ color: t.text, fontSize: 20, letterSpacing: -0.3, ...font.extrabold, flexShrink: 1 }, tabular]} numberOfLines={1}>
          {formatAmount(group.total, group.coin.config.decimals)}
        </Text>
      ) : null}
    </View>
  );
}

/** The followed coins as cards, each listing the accounts that hold it; an account opens its screen. */
export function CoinGroups({
  groups,
  loading,
  onOpen,
  pendingFor,
}: {
  groups: CoinGroup[];
  loading: boolean;
  onOpen: (coin: CoinInfo, slot: number) => void;
  pendingFor?: (coin: CoinInfo, view: AccountView) => OnTheWay;
}) {
  const t = useTheme();
  return (
    <>
      {groups.map((g) => (
        <Card key={g.coin.hex} padded={false} style={{ gap: 0 }}>
          <CoinHead group={g} total />
          <View style={{ borderTopWidth: 1, borderTopColor: t.border }}>
            {g.views.map((v, i) => (
              <AccountLine key={v.account.slot} first={i === 0} coin={g.coin} view={v} onPress={() => onOpen(g.coin, v.account.slot)} pending={pendingFor?.(g.coin, v)} />
            ))}
          </View>
          {g.views.length === 0 ? <Caption style={{ paddingHorizontal: spacing.lg, paddingVertical: spacing.md }}>{loading ? 'Loading balances…' : 'No accounts yet'}</Caption> : null}
        </Card>
      ))}
    </>
  );
}

/** Choosing the account to send from: every account of every followed coin, coin by coin. */
export function AccountPicker({
  visible,
  onClose,
  groups,
  selected,
  onSelect,
}: {
  visible: boolean;
  onClose: () => void;
  groups: CoinGroup[];
  selected: { coin: string; slot: number } | null;
  onSelect: (coin: CoinInfo, slot: number) => void;
}) {
  const t = useTheme();
  return (
    <Sheet visible={visible} onClose={onClose} title="Send from">
      {groups.map((g) => (
        <View key={g.coin.hex} style={{ borderWidth: 1, borderColor: t.border, borderRadius: 16, overflow: 'hidden' }}>
          <CoinHead group={g} total={false} />
          <View style={{ borderTopWidth: 1, borderTopColor: t.border }}>
            {g.views.map((v, i) => (
              <AccountLine
                key={v.account.slot}
                first={i === 0}
                coin={g.coin}
                view={v}
                symbol
                selected={selected?.coin === g.coin.hex && selected?.slot === v.account.slot}
                onPress={() => {
                  onSelect(g.coin, v.account.slot);
                  onClose();
                }}
              />
            ))}
          </View>
        </View>
      ))}
      {groups.length === 0 ? <Caption>No coins followed yet; add them in Settings.</Caption> : null}
    </Sheet>
  );
}
