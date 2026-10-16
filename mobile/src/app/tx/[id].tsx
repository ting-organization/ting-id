import React from 'react';
import { Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { equal, fromUtf8, hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatTime } from '@/chain/format';
import * as idp from '@/chain/identity';
import { ActivityItem, useTransaction } from '@/state/chain';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { Banner, Button, Caption, Card, IconCircle, IdChip, Loading, Row, Screen, Sub, toneColors } from '@/ui/components';
import { describeActivity, sentence } from '@/ui/describe';
import { font, spacing, tabular, type, useTheme } from '@/ui/theme';

export default function TransactionScreen() {
  const t = useTheme();
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const w = useWorkspace();
  const submitted = useStore((s) => s.submitted.find((x) => x.id === id));
  const { lookup, error } = useTransaction(id ?? null);
  const mine = w.accounts.map((a) => a.account);
  const occurrence = lookup?.occurrences[lookup.occurrences.length - 1];
  const receipt = occurrence?.receipt;
  const printable = receipt && receipt.result.length > 0 && receipt.result.every((b) => b >= 0x20 && b < 0x7f);
  const deferred = lookup?.deferred ?? null;
  const state = receipt
    ? receipt.status === 0
      ? 'ok'
      : 'failed'
    : lookup?.pending
      ? 'pending'
      : deferred
        ? 'deferred'
        : lookup
          ? 'unknown'
          : 'loading';
  const header = {
    ok: { icon: 'checkmark' as const, label: 'Confirmed', tone: 'success' as const },
    failed: { icon: 'close' as const, label: receipt?.statusName ?? 'Failed', tone: 'danger' as const },
    pending: { icon: 'time-outline' as const, label: 'Waiting for a block', tone: 'warning' as const },
    deferred: { icon: 'hourglass-outline' as const, label: 'Queued on the node', tone: 'accent' as const },
    unknown: { icon: 'help' as const, label: 'Unknown to the node', tone: 'neutral' as const },
    loading: { icon: 'ellipsis-horizontal' as const, label: 'Looking it up…', tone: 'neutral' as const },
  }[state];
  const c = toneColors(t, header.tone);

  return (
    <Screen belowHeader>
      <View style={{ alignItems: 'center', gap: 10, paddingVertical: spacing.lg }}>
        <IconCircle name={header.icon} color={c.fg} bg={c.bg} size={72} />
        <Text style={[type.heading, { color: t.text, textAlign: 'center' }]}>{submitted?.summary ?? 'Transaction'}</Text>
        <Text style={{ color: c.fg, ...font.bold }}>{header.label}</Text>
      </View>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      {state === 'loading' ? <Loading /> : null}
      {state === 'deferred' && deferred ? (
        <Banner tone="info" icon="hourglass-outline">
          Held by the node until {formatTime(deferred.notBefore)}, then included as soon as it succeeds
          {deferred.lastResult ? ` (last attempt: ${deferred.lastResult})` : ''}.
        </Banner>
      ) : null}
      {state === 'failed' && receipt ? <Banner tone="danger">{printable ? fromUtf8(receipt.result) : `failed (${receipt.statusName})`}</Banner> : null}

      <Card style={{ gap: 0 }} padded={false}>
        <Detail label="Transaction" value={<IdChip value={id ?? ''} />} first />
        {occurrence ? <Detail label="Block" value={<Text style={{ color: t.text }}>{occurrence.height}</Text>} /> : null}
        {receipt && w.advanced ? (
          <Detail
            label="Cost"
            value={<Caption>{receipt.used.map((u) => u.toLocaleString()).join(' / ')} C / S / D / G</Caption>}
          />
        ) : null}
        {receipt && receipt.status === 0 && receipt.result.length === 32 ? <Detail label="Result" value={<IdChip value={hex(receipt.result)} />} /> : null}
        {receipt && receipt.status === 0 && printable && receipt.result.length !== 32 ? (
          <Detail label="Result" value={<Caption>{fromUtf8(receipt.result)}</Caption>} />
        ) : null}
      </Card>

      {receipt && receipt.events.length > 0 ? (
        <>
          <Sub>What happened</Sub>
          <Card padded={false} style={{ gap: 0 }}>
            {receipt.events.map((e, i) => {
              const item: ActivityItem = { height: occurrence!.height, time: 0, transaction: id ?? '', contract: e.contract };
              if (w.coinById.has(hex(e.contract))) item.coin = coin.decodeCoinEvent(e.payload) ?? undefined;
              else if (w.info && equal(e.contract, w.info.canonical.registry)) item.identity = idp.decodeIdEvent(e.payload) ?? undefined;
              const d = item.coin || item.identity ? describeActivity(item, w.coinById, w.names, mine) : null;
              const tc = toneColors(t, d?.tone ?? 'neutral');
              return (
                <Row key={i} style={{ paddingHorizontal: spacing.lg, paddingVertical: 12, borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }} gap={12}>
                  <IconCircle name={d?.icon ?? 'ellipse-outline'} color={tc.fg} bg={tc.bg} />
                  <View style={{ flex: 1, gap: 2 }}>
                    <Text style={[type.body, { color: t.text, ...font.bold }]}>{d?.title ?? `Event from ${hex(e.contract).slice(0, 12)}…`}</Text>
                    {d?.detail ? <Caption>{d.detail}</Caption> : null}
                  </View>
                  {d?.amount ? <Text style={[type.body, { color: d.amountTone ? toneColors(t, d.amountTone).fg : t.text, ...font.bold }, tabular]}>{d.amount}</Text> : null}
                </Row>
              );
            })}
          </Card>
        </>
      ) : null}
      <Button title="Done" kind="secondary" onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}

function Detail({ label, value, first }: { label: string; value: React.ReactNode; first?: boolean }) {
  const t = useTheme();
  return (
    <Row style={{ justifyContent: 'space-between', paddingHorizontal: spacing.lg, paddingVertical: 12, borderTopWidth: first ? 0 : 1, borderTopColor: t.border }}>
      <Caption>{label}</Caption>
      {value}
    </Row>
  );
}
