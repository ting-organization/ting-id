import React from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { hex } from '@/chain/bytes';
import { dayLabel, formatClock } from '@/chain/format';
import type { Activity } from '@/state/activity';
import type { CoinInfo } from '@/state/coins';
import { CoinMark } from './CoinPicker';
import { Avatar, Button, Caption, Card, Empty, IconCircle, Loading, toneColors } from './components';
import { useStore } from '@/state/store';
import { Names, describeActivity, groupMinerPayments } from './describe';
import { font, spacing, tabular, useTheme } from './theme';

/** Each item's day heading: shown above the first item of a day; an item of unknown time stays under the one before it. */
function dayHeadings(days: (string | null)[]): (string | null)[] {
  let last: string | null = null;
  return days.map((day) => {
    if (!day || day === last) return null;
    last = day;
    return day;
  });
}

/**
 * The newest activity first, by day, a page at a time: each row names who it
 * was with, what happened and when. The screen shows more as it is scrolled to
 * the end (`Screen`'s `onEndReached` calls `showMore`), and once everything
 * found is shown, older blocks are searched on request.
 */
export function ActivityList({ activity, coins, names, mine }: { activity: Activity; coins: Map<string, CoinInfo>; names: Names; mine: Uint8Array[] }) {
  const t = useTheme();
  const router = useRouter();
  const advanced = useStore((s) => s.settings.advanced);
  const { loading, searching, hidden, searchedTo } = activity;
  const items = groupMinerPayments(activity.items, dayLabel);
  const headings = dayHeadings(items.map((it) => (it.time ? dayLabel(it.time) : null)));
  return (
    <Card padded={false} style={{ gap: 0 }}>
      {loading && items.length === 0 ? <Loading /> : null}
      {!loading && items.length === 0 ? (
        searchedTo === null ? (
          <Empty icon="receipt-outline" title="No activity yet" body="Payments, scheduled actions and identity changes show up here." />
        ) : (
          <Empty icon="receipt-outline" title="No recent activity" body={`Nothing in the blocks since block ${searchedTo}.`} />
        )
      ) : null}
      {items.map((item, i) => {
        const d = describeActivity(item, coins, names, mine, advanced);
        const heading = headings[i];
        const c = coins.get(hex(item.contract));
        const left = d.party?.coin && c ? (
          <CoinMark coin={c} size={38} />
        ) : d.party ? (
          <Avatar name={d.party.name} color={t.primary} size={38} soft />
        ) : (
          <IconCircle name={d.icon} color={toneColors(t, d.tone).fg} bg={toneColors(t, d.tone).bg} size={38} />
        );
        const what = d.party ? d.short : d.detail;
        const when = item.time ? formatClock(item.time) : null;
        return (
          <View key={`${item.transaction}-${i}`}>
            {heading ? (
              <Text
                style={{
                  paddingHorizontal: spacing.lg,
                  paddingTop: i === 0 ? 12 : 16,
                  paddingBottom: 2,
                  color: t.faint,
                  fontSize: 11.5,
                  letterSpacing: 0.6,
                  textTransform: 'uppercase',
                  ...font.bold,
                }}>
                {heading}
              </Text>
            ) : null}
            <Pressable
              onPress={() => router.push({ pathname: '/tx/[id]', params: { id: item.transaction } })}
              style={({ pressed }) => ({
                paddingVertical: 11,
                paddingHorizontal: spacing.lg,
                borderTopWidth: i === 0 || heading ? 0 : 1,
                borderTopColor: t.border,
                backgroundColor: pressed ? t.cardAlt : 'transparent',
                flexDirection: 'row',
                alignItems: 'center',
                gap: 12,
              })}>
              {left}
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }} numberOfLines={1}>
                  {d.party?.name ?? d.title}
                </Text>
                {what || when ? (
                  <Caption numberOfLines={2}>
                    {what}
                    {what && when ? ' · ' : ''}
                    {when}
                  </Caption>
                ) : null}
              </View>
              {d.amount ? (
                <Text style={[{ color: d.amountTone ? toneColors(t, d.amountTone).fg : t.text, fontSize: 14.5, ...font.bold }, tabular]}>{d.amount}</Text>
              ) : d.planned ? (
                <Text style={[{ color: t.faint, fontSize: 14.5, ...font.bold }, tabular]}>{d.planned}</Text>
              ) : null}
            </Pressable>
          </View>
        );
      })}
      {hidden > 0 ? (
        <View style={{ borderTopWidth: 1, borderTopColor: t.border, padding: spacing.sm, alignItems: 'center' }}>
          <Button title="Show more" kind="ghost" small onPress={activity.showMore} style={{ alignSelf: 'center' }} />
        </View>
      ) : searching && !loading ? (
        <View style={{ borderTopWidth: items.length ? 1 : 0, borderTopColor: t.border, padding: spacing.md, flexDirection: 'row', justifyContent: 'center', gap: 8 }}>
          <ActivityIndicator size="small" color={t.muted} />
          <Caption>Searching older blocks…</Caption>
        </View>
      ) : searchedTo !== null && !loading ? (
        <View style={{ borderTopWidth: items.length ? 1 : 0, borderTopColor: t.border, padding: spacing.sm, alignItems: 'center', gap: 2 }}>
          <Button title="Load older activity" kind="ghost" small icon="time-outline" onPress={activity.searchOlder} style={{ alignSelf: 'center' }} />
          <Caption>Searched back to block {searchedTo}</Caption>
        </View>
      ) : null}
    </Card>
  );
}
