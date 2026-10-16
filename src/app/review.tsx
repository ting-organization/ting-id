// A payment once more before it goes: amount, both accounts, when, fees and
// what is left, then the one button that sends it. Send stays beneath, and
// Edit goes back to it with everything as entered.
import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { formatAmount, formatClock } from '@/chain/format';
import { usePaymentDraft } from '@/state/paymentDraft';
import { useWorkspace } from '@/state/workspace';
import { CoinMark } from '@/ui/CoinPicker';
import { Banner, Button, Caption, Detail, DetailCard, DetailValue, Row, Screen } from '@/ui/components';
import { lockOf, sentence } from '@/ui/describe';
import { SaveContactSheet } from '@/ui/SaveContact';
import { font, spacing, tabular, type, useTheme } from '@/ui/theme';
import { formatDay } from '@/ui/when';

export default function ReviewScreen() {
  const t = useTheme();
  const router = useRouter();
  const w = useWorkspace();
  const draft = usePaymentDraft((s) => s.draft);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  if (!draft) {
    return (
      <Screen belowHeader>
        <Banner tone="warning">Nothing to review: start again from Send.</Banner>
        <Button title="Back" kind="secondary" onPress={() => (router.canGoBack() ? router.back() : router.replace('/send'))} />
      </Screen>
    );
  }

  const c = draft.coin;
  const amount = (v: bigint) => `${formatAmount(v, c.config.decimals)} ${c.config.symbol}`;
  const toName = w.names.get(draft.to.account) ?? null;
  const later = draft.runsAt !== null;
  const fromSlot = draft.from.label.split(' · ').pop();
  // A time-locked account: its lock, not the chosen time, decides when it runs.
  const lockDecides = later && draft.lock > 0 && (draft.chosenAt === null || draft.chosenAt < draft.runsAt!);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      const id = await draft.submit();
      router.replace({ pathname: '/tx/[id]', params: { id, sent: '1' } });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Screen belowHeader>
      <View style={{ alignItems: 'center', gap: 8, paddingVertical: spacing.md }}>
        <CoinMark coin={c} size={56} />
        <Row gap={8} style={{ alignItems: 'baseline' }}>
          <Text style={[{ color: t.text, fontSize: 38, letterSpacing: -1.2, ...font.extrabold }, tabular]}>{formatAmount(draft.amount, c.config.decimals)}</Text>
          <Text style={{ color: t.faint, fontSize: 18, ...font.bold }}>{c.config.symbol}</Text>
        </Row>
        <Caption>{later ? `Runs ${formatDay(draft.runsAt!)} at ${formatClock(draft.runsAt!)}` : 'Goes as soon as a block includes it'}</Caption>
      </View>

      <DetailCard>
        <Detail label="From" first>
          <DetailValue>{draft.from.label}</DetailValue>
        </Detail>
        <View style={{ borderTopWidth: 1, borderTopColor: t.border, paddingHorizontal: spacing.lg, paddingVertical: 12, gap: 6 }}>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ color: t.muted, fontSize: 13.5, ...font.semibold }}>To</Text>
            <DetailValue>{toName ?? 'New recipient'}</DetailValue>
          </Row>
          <Text selectable style={[type.mono, { color: t.text, fontSize: 12.5, lineHeight: 18 }]}>
            {draft.to.account}
          </Text>
          {!toName ? <Button title="Save as contact" kind="ghost" small icon="person-add-outline" onPress={() => setSaving(true)} /> : null}
        </View>
        <Detail label="When">
          <DetailValue>{later ? `${formatDay(draft.runsAt!)}, ${formatClock(draft.runsAt!)}` : 'Now'}</DetailValue>
        </Detail>
        <Detail label="Fee">
          <DetailValue>{draft.fee ? draft.fee.replace(/^Fee /, '') : 'None'}</DetailValue>
        </Detail>
        {draft.runFee > 0n ? (
          <Detail label="Run fee">
            <DetailValue>{amount(draft.runFee)}</DetailValue>
          </Detail>
        ) : null}
        <Detail label={`Total from ${fromSlot}`}>
          <DetailValue>{amount(draft.total)}</DetailValue>
        </Detail>
        <Detail label="Available after">
          <DetailValue>{amount(draft.available - draft.total)}</DetailValue>
        </Detail>
      </DetailCard>

      {later ? (
        <Caption style={{ paddingHorizontal: 4 }}>
          {lockDecides ? `Withdrawals from ${fromSlot} wait for its ${lockOf(draft.lock)}. ` : ''}
          {amount(draft.amount + draft.runFee)} is reserved until it runs, and you can cancel it from the wallet until then.
        </Caption>
      ) : null}
      {!toName ? (
        <Banner tone="info" icon="person-outline">
          This account is not in your contacts. Check the address with whoever you pay: a payment cannot be taken back once it runs.
        </Banner>
      ) : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Button title={later ? 'Confirm and schedule' : 'Confirm and send'} large loading={busy} onPress={confirm} />
      <Button title="Edit" kind="ghost" onPress={() => router.back()} disabled={busy} />
      <SaveContactSheet account={draft.to.account} visible={saving} onClose={() => setSaving(false)} />
    </Screen>
  );
}
