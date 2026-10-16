// What Ting ID gives, for a phone with no user yet, and the two ways in: a
// new identity, or one restored from its recovery phrase or its ID.
import React from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { Button, Caption, Card, IconCircle, IconName, ListItem } from './components';
import { useTheme } from './theme';

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  { icon: 'wallet-outline', title: 'Spending', text: 'Pay and get paid at once.' },
  { icon: 'lock-closed-outline', title: 'Savings', text: 'Withdrawals wait a few days, and you can stop them meanwhile.' },
  { icon: 'shield-checkmark-outline', title: 'Recovery', text: 'Your recovery phrase and your guardians get you back in on a new phone.' },
];

export function Welcome() {
  const t = useTheme();
  const router = useRouter();
  return (
    <>
      <Card padded={false} style={{ gap: 0 }}>
        {FEATURES.map((f, i) => (
          <View key={f.title} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem left={<IconCircle name={f.icon} color={t.primary} bg={t.primarySoft} />} title={f.title} subtitle={f.text} />
          </View>
        ))}
      </Card>
      <Button title="Create an identity" large icon="add" onPress={() => router.push({ pathname: '/user/new', params: { mode: 'new' } })} />
      <Button title="Restore an identity" kind="secondary" icon="refresh-outline" onPress={() => router.push({ pathname: '/user/new', params: { mode: 'restore' } })} />
      <Caption style={{ textAlign: 'center' }}>Restore with your recovery phrase, or with the ID that another of your devices or your guardians give you.</Caption>
    </>
  );
}
