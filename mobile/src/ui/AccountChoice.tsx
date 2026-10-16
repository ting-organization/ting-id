// Picking one of the user's accounts, each with what its lock means.
import React from 'react';
import { View } from 'react-native';
import type { UserAccount } from '@/state/chain';
import { IconCircle, ListItem, Pill } from './components';
import { relativeDuration } from './describe';
import { useTheme } from './theme';

export function AccountChoice({
  accounts,
  slot,
  onSlot,
  note,
  badge,
}: {
  accounts: UserAccount[];
  slot: number | null;
  onSlot: (slot: number) => void;
  /** What choosing the account means, in place of its lock. */
  note?: (a: UserAccount) => string;
  /** A short label to show on one account, such as "recommended". */
  badge?: { slot: number; label: string };
}) {
  const t = useTheme();
  return (
    <View style={{ borderWidth: 1, borderColor: t.border, borderRadius: 16, overflow: 'hidden', backgroundColor: t.card }}>
      {accounts.map((a, i) => {
        const locked = a.delay > 0;
        return (
          <View key={a.slot} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem
              left={<IconCircle name={locked ? 'lock-closed-outline' : 'wallet-outline'} color={locked ? t.accent : t.primary} bg={locked ? t.accentSoft : t.primarySoft} size={32} />}
              title={a.label}
              subtitle={note ? note(a) : locked ? `withdrawals wait ${relativeDuration(a.delay)}` : 'instant'}
              selected={a.slot === slot}
              right={badge?.slot === a.slot ? <Pill label={badge.label} tone="success" small /> : null}
              onPress={() => onSlot(a.slot)}
            />
          </View>
        );
      })}
    </View>
  );
}
