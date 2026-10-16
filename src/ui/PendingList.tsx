// Transactions this device sent that no block has included yet, and ones that
// failed: where each stands, with Retry and Dismiss where they help.
import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import type { PendingItem } from '@/state/pending';
import { Button, Caption, Card, IconCircle, Row, SectionHeader, toneColors } from './components';
import { describeWatch } from './describe';
import { font, spacing, useTheme } from './theme';

export function PendingList({
  items,
  onRetry,
  onDismiss,
}: {
  items: PendingItem[];
  onRetry: (id: string) => Promise<string | null>;
  onDismiss: (id: string) => void;
}) {
  const t = useTheme();
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  if (items.length === 0) return null;
  return (
    <>
      <SectionHeader
        title={`Waiting (${items.length})`}
        icon="time-outline"
        info="Transactions this device sent that no block has included yet. If the node loses one, the app sends the same transaction again; it can never run twice."
      />
      <Card padded={false} style={{ gap: 0 }}>
        {items.map(({ tx, watch, canRetry }, i) => {
          const d = describeWatch(watch, tx.at);
          const c = toneColors(t, d.tone);
          const stuck = watch.kind === 'failed' || watch.kind === 'lost';
          return (
            <View key={tx.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
              <Pressable
                onPress={() => router.push({ pathname: '/tx/[id]', params: { id: tx.id } })}
                style={({ pressed }) => ({ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 12, paddingHorizontal: spacing.lg, backgroundColor: pressed ? t.cardAlt : 'transparent' })}>
                <IconCircle name={d.icon} color={c.fg} bg={c.bg} />
                <View style={{ flex: 1, gap: 2 }}>
                  <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }}>{tx.summary}</Text>
                  <Caption numberOfLines={3}>{d.detail}</Caption>
                </View>
              </Pressable>
              {stuck ? (
                <Row style={{ paddingHorizontal: spacing.lg, paddingBottom: 12, justifyContent: 'flex-end' }}>
                  {canRetry ? (
                    <Button
                      title="Retry"
                      kind="secondary"
                      small
                      icon="refresh-outline"
                      loading={busy === tx.id}
                      onPress={async () => {
                        setBusy(tx.id);
                        await onRetry(tx.id);
                        setBusy(null);
                      }}
                    />
                  ) : null}
                  <Button title="Dismiss" kind="ghost" small onPress={() => onDismiss(tx.id)} />
                </Row>
              ) : null}
            </View>
          );
        })}
      </Card>
    </>
  );
}
