import React, { useCallback } from 'react';
import { BackHandler, Text, View } from 'react-native';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { equal, fromUtf8, hex, unhex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount, formatTime, nowSeconds } from '@/chain/format';
import * as idp from '@/chain/identity';
import { ActivityItem, useTransaction } from '@/state/chain';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { Banner, Button, Caption, Card, IconCircle, IdChip, Loading, Row, Screen, Sub, toneColors } from '@/ui/components';
import type { CoinInfo } from '@/state/coins';
import { describeActivity, sentence, statusWords } from '@/ui/describe';
import { font, spacing, tabular, type, useTheme } from '@/ui/theme';

export default function TransactionScreen() {
  const t = useTheme();
  // `sent`: opened right after sending, so leaving goes to the wallet.
  // `unsure`: the node's answer to the submission was lost.
  const { id, sent, unsure } = useLocalSearchParams<{ id: string; sent?: string; unsure?: string }>();
  const router = useRouter();
  const w = useWorkspace();
  const leave = useCallback(() => {
    if (sent) router.dismissTo('/');
    else if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [router, sent]);
  useFocusEffect(
    useCallback(() => {
      if (!sent) return;
      const sub = BackHandler.addEventListener('hardwareBackPress', () => {
        leave();
        return true;
      });
      return () => sub.remove();
    }, [sent, leave]),
  );
  const submitted = useStore((s) => s.submitted.find((x) => x.id === id));
  const { lookup, error, missing } = useTransaction(id ?? null);
  const mine = w.accounts.map((a) => a.account);
  const occurrence = lookup?.occurrences[lookup.occurrences.length - 1];
  const receipt = occurrence?.receipt;
  const printable = receipt && receipt.result.length > 0 && receipt.result.every((b) => b >= 0x20 && b < 0x7f);
  const deferred = lookup?.deferred ?? null;
  const events = (receipt?.events ?? []).map((e) => ({ contract: e.contract, coin: w.coinById.has(hex(e.contract)) ? (coin.decodeCoinEvent(e.payload) ?? undefined) : undefined, payload: e.payload }));
  // A payment for later is in a block once scheduled; it runs at its time, in another transaction.
  const scheduled = events.map((e) => e.coin).find((e): e is Extract<coin.CoinEvent, { kind: 'scheduled' }> => e?.kind === 'scheduled');
  const state = receipt
    ? receipt.status !== 0
      ? 'failed'
      : scheduled
        ? 'scheduled'
        : 'ok'
    : lookup?.pending
      ? 'pending'
      : deferred
        ? 'deferred'
        : lookup || missing
          ? 'unknown'
          : 'loading';
  const runsAt = scheduled ? Number(scheduled.executeAt) : 0;
  const header = {
    ok: { icon: 'checkmark' as const, label: 'Confirmed', tone: 'success' as const },
    scheduled: {
      icon: 'calendar-outline' as const,
      label: runsAt > nowSeconds() ? `Scheduled · runs ${formatTime(runsAt)}` : `Scheduled for ${formatTime(runsAt)}`,
      tone: 'accent' as const,
    },
    failed: { icon: 'close' as const, label: 'Failed', tone: 'danger' as const },
    pending: { icon: 'time-outline' as const, label: 'Waiting for a block', tone: 'warning' as const },
    deferred: { icon: 'hourglass-outline' as const, label: 'Held for its time', tone: 'accent' as const },
    unknown: { icon: 'help' as const, label: 'Unknown to the node', tone: 'neutral' as const },
    loading: { icon: 'ellipsis-horizontal' as const, label: 'Looking it up…', tone: 'neutral' as const },
  }[state];
  const c = toneColors(t, header.tone);
  // A fee or tip is a transfer that the producer payment after it pays on.
  const isFee = (i: number) => {
    const ev = events[i].coin;
    const paid = events[i + 1]?.coin;
    return ev?.kind === 'transfer' && paid?.kind === 'producerPayment' && equal(paid.payee, ev.to) && paid.amount === ev.amount;
  };
  const payment = paymentOf(events, isFee, mine, w.coinById) ?? (submitted?.to ? { label: 'To', account: unhex(submitted.to) } : null);
  const paymentName = payment ? w.names.get(hex(payment.account)) : undefined;

  return (
    <Screen belowHeader>
      <View style={{ alignItems: 'center', gap: 10, paddingVertical: spacing.lg }}>
        <IconCircle name={header.icon} color={c.fg} bg={c.bg} size={72} />
        <Text style={[type.heading, { color: t.text, textAlign: 'center' }]}>{submitted?.summary ?? 'Transaction'}</Text>
        <Text style={{ color: c.fg, ...font.bold }}>{header.label}</Text>
      </View>
      {unsure && (state === 'pending' || state === 'unknown' || state === 'loading') ? (
        <Banner tone="warning">
          No answer from the node. It may still go through: it is listed under Waiting on the wallet, and Ting ID sends it again unchanged if the node lost it. Don’t send it again.
        </Banner>
      ) : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      {state === 'loading' ? <Loading /> : null}
      {state === 'deferred' && deferred ? (
        <Banner tone="info" icon="hourglass-outline">
          Held by the node until {formatTime(deferred.notBefore)}, then included as soon as it succeeds
          {deferred.lastResult ? ` (last attempt: ${deferred.lastResult})` : ''}.
        </Banner>
      ) : null}
      {state === 'unknown' && !unsure ? (
        submitted?.problem ? (
          <Banner tone="danger">{sentence(submitted.problem)}</Banner>
        ) : (
          <Banner tone="info" icon="help-circle-outline">
            The node does not know this transaction. One just sent may still be on its way; one the node dropped is sent again from Waiting on the wallet.
          </Banner>
        )
      ) : null}
      {state === 'failed' && receipt ? <Banner tone="danger">{sentence(printable ? fromUtf8(receipt.result) : statusWords(receipt.statusName))}</Banner> : null}

      {payment ? (
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Caption>{payment.label}</Caption>
            <Text style={[type.body, { color: t.text, ...font.bold }]}>{paymentName ?? 'Not in your contacts'}</Text>
          </Row>
          <Text selectable style={[type.mono, { color: t.text, fontSize: 12.5, lineHeight: 18 }]}>
            {hex(payment.account)}
          </Text>
          {payment.amount && payment.coin ? (
            <Row style={{ justifyContent: 'space-between' }}>
              <Caption>Amount</Caption>
              <Text style={[type.body, { color: t.text, ...font.bold }, tabular]}>
                {formatAmount(payment.amount, payment.coin.config.decimals)} {payment.coin.config.symbol}
              </Text>
            </Row>
          ) : null}
        </Card>
      ) : null}

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
            {events.map((e, i) => {
              const item: ActivityItem = { height: occurrence!.height, time: 0, transaction: id ?? '', contract: e.contract, coin: e.coin, fee: isFee(i) || undefined };
              if (!e.coin && w.info && equal(e.contract, w.info.canonical.registry)) item.identity = idp.decodeIdEvent(e.payload) ?? undefined;
              const d = item.coin || item.identity ? describeActivity(item, w.coinById, w.names, mine, w.advanced) : null;
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
      <Button title={sent ? 'Back to wallet' : 'Done'} kind="secondary" onPress={leave} />
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}

/**
 * The payment of the transaction: who it went to, or for a payment to one of
 * these accounts who it came from, and how much. A fee is no payment, and a
 * scheduled payment counts as made here.
 */
function paymentOf(
  events: { contract: Uint8Array; coin?: coin.CoinEvent }[],
  isFee: (i: number) => boolean,
  mine: Uint8Array[],
  coins: Map<string, CoinInfo>,
): { label: 'To' | 'From'; account: Uint8Array; amount?: bigint; coin?: CoinInfo } | null {
  const isMine = (a: Uint8Array) => mine.some((m) => equal(m, a));
  for (let i = 0; i < events.length; i++) {
    const ev = events[i].coin;
    const c = coins.get(hex(events[i].contract));
    if (!ev || isFee(i)) continue;
    if (ev.kind === 'transfer' && isMine(ev.from)) return { label: 'To', account: ev.to, amount: ev.amount, coin: c };
    if (ev.kind === 'transfer' && isMine(ev.to)) return { label: 'From', account: ev.from, amount: ev.amount, coin: c };
    if (ev.kind === 'scheduled' && ev.effect.kind === 'transfer') return { label: 'To', account: ev.effect.to, amount: ev.effect.amount, coin: c };
  }
  return null;
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
