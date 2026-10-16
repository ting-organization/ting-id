// Scheduled actions of the user's accounts: what is waiting to run, who runs
// it, and the buttons to run, queue or cancel it, as rows and as a whole
// section; a cancel shows the fee it pays in its confirmation.
import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useRouter } from 'expo-router';
import * as coin from '@/chain/coin';
import { formatAmount, nowSeconds, relativeTime } from '@/chain/format';
import { RunStatus, runKey, useRunStatuses } from '@/state/chain';
import type { AccountView, CoinInfo } from '@/state/coins';
import { Acting, useFee } from '@/state/fees';
import { useScheduledActions } from '@/state/scheduled';
import type { useWorkspace } from '@/state/workspace';
import { Banner, Button, Caption, Card, IconCircle, Info, Row, initialsOf } from './components';
import { FeeRow } from './FeeRow';
import { ConfirmOptions, ConfirmSheet } from './confirm';
import { describeEffect, nameOf, Names, sentence } from './describe';
import { font, spacing, useTheme } from './theme';

export interface ScheduledEntry {
  coin: CoinInfo;
  view: AccountView;
  sequence: bigint;
  record: coin.ScheduledRecord;
}

/** Every pending scheduled action across the given coins, soonest first. */
export function scheduledOf(coins: CoinInfo[], views: Map<string, AccountView[]>): ScheduledEntry[] {
  const out: ScheduledEntry[] = [];
  for (const c of coins) for (const view of views.get(c.hex) ?? []) for (const s of view.scheduled) out.push({ coin: c, view, sequence: s.sequence, record: s.record });
  return out.sort((a, b) => (a.record.executeAt < b.record.executeAt ? -1 : 1));
}

/** What the caption says about who runs the action. */
export function runNote(status: RunStatus | undefined, due: boolean): string {
  switch (status) {
    case 'queued':
      return due ? 'the node is about to run it' : 'the node runs it when due';
    case 'pending':
      return 'run awaiting a block';
    case 'ran':
      return 'ran';
    default:
      return due ? 'due now' : 'not set to run yet';
  }
}

/** A scheduled action as a title: what it moves and to whom. */
function effectTitle(e: ScheduledEntry, names: Names): string {
  const effect = e.record.effect;
  if (effect.kind === 'transfer') return `${formatAmount(effect.amount, e.coin.config.decimals)} ${e.coin.config.symbol} to ${nameOf(names, effect.to)}`;
  return describeEffect(effect, e.coin, names);
}

export function ScheduledRows({
  entries,
  names,
  runs,
  busy,
  onCancel,
  onRun,
  onQueue,
}: {
  entries: ScheduledEntry[];
  names: Names;
  /** Run status per `scheduledKey`, from `useRunStatuses`. */
  runs: Map<string, RunStatus>;
  busy: string | null;
  onCancel: (entry: ScheduledEntry) => void;
  onRun: (entry: ScheduledEntry) => void;
  onQueue: (entry: ScheduledEntry) => void;
}) {
  const t = useTheme();
  return (
    <>
      {entries.map((e, i) => {
        const due = Number(e.record.executeAt) <= nowSeconds();
        const key = scheduledKey(e);
        const status = runs.get(runKey(runRefOf(e))) ?? null;
        const handled = status === 'queued' || status === 'pending';
        const effect = e.record.effect;
        const who = effect.kind === 'transfer' || effect.kind === 'mint' ? nameOf(names, effect.to) : null;
        return (
          <View key={key} style={{ gap: 10, paddingVertical: 12, paddingHorizontal: spacing.lg, borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.warningLine }}>
            <Row gap={12}>
              {who ? (
                <View style={{ width: 38, height: 38, borderRadius: 19, backgroundColor: t.warningLine, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: t.warning, fontSize: 14, ...font.extrabold }}>{initialsOf(who)}</Text>
                </View>
              ) : (
                <IconCircle name={due ? 'play-outline' : 'time-outline'} color={t.warning} bg={t.warningLine} size={38} />
              )}
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }}>{effectTitle(e, names)}</Text>
                <Caption style={{ color: t.warning }}>
                  From {e.view.account.label} · {due ? '' : `${relativeTime(e.record.executeAt)} · `}
                  {runNote(status, due)}
                  {e.record.tip > 0n ? ` · run fee ${formatAmount(e.record.tip, e.coin.config.decimals)} ${e.coin.config.symbol}` : ''}
                </Caption>
              </View>
              <Button title="Cancel" kind="danger" small loading={busy === `cancel-${key}`} onPress={() => onCancel(e)} />
            </Row>
            {due && !handled ? (
              <Row style={{ paddingLeft: 50 }}>
                <Button title="Run now" kind="secondary" small icon="play" loading={busy === `run-${key}`} onPress={() => onRun(e)} />
              </Row>
            ) : !due && !handled ? (
              <Row style={{ paddingLeft: 50 }}>
                <Button title="Run when due" kind="secondary" small icon="time-outline" loading={busy === `queue-${key}`} onPress={() => onQueue(e)} />
              </Row>
            ) : null}
          </View>
        );
      })}
    </>
  );
}

export function runRefOf(e: ScheduledEntry) {
  return { coinId: e.coin.id, account: e.view.account.account, sequence: e.sequence };
}

export function scheduledKey(e: ScheduledEntry): string {
  return `${e.coin.hex}-${e.view.account.slot}-${e.sequence}`;
}

/**
 * The scheduled actions `entries` with Cancel, Run now and Run when due, Cancel
 * all, and the fee the cancels pay. `acting`: the coin and account of an
 * account screen; none on the wallet, which lists every account's.
 */
export function ScheduledSection({
  w,
  entries,
  acting,
  info,
}: {
  w: ReturnType<typeof useWorkspace>;
  entries: ScheduledEntry[];
  acting: Acting | null;
  info?: string;
}) {
  const t = useTheme();
  const router = useRouter();
  const fee = useFee(w, acting);
  const runs = useRunStatuses(entries.map(runRefOf));
  const actions = useScheduledActions(w, fee, (id) => router.push({ pathname: '/tx/[id]', params: { id } }));
  // The cancel being confirmed: its sheet shows the fee, which can be changed there.
  const [asking, setAsking] = useState<{ entries: ScheduledEntry[]; label: string; options: ConfirmOptions } | null>(null);
  if (entries.length === 0) return null;
  const cancelOne = (e: ScheduledEntry) =>
    setAsking({
      entries: [e],
      label: `cancel-${scheduledKey(e)}`,
      options: {
        title: 'Cancel this scheduled action?',
        body: `${describeEffect(e.record.effect, e.coin, w.names)}, from ${e.view.account.label}. It will not run.`,
        confirm: 'Cancel action',
        back: 'Keep it',
        danger: true,
      },
    });
  const cancelAll = () =>
    setAsking({
      entries,
      label: 'cancel-all',
      options: { title: `Cancel all ${entries.length} scheduled actions?`, body: 'None of them will run.', confirm: 'Cancel all', back: 'Keep them', danger: true },
    });
  return (
    <>
      {actions.error ? <Banner tone="danger">{sentence(actions.error)}</Banner> : null}
      <Card tone="warning" padded={false} style={{ gap: 0 }}>
        <Row style={{ paddingHorizontal: spacing.lg, paddingTop: 14, paddingBottom: 4 }}>
          <Ionicons name="time-outline" size={16} color={t.warning} />
          <Text style={{ color: t.warning, fontSize: 13.5, ...font.extrabold }}>
            {entries.length} scheduled {entries.length === 1 ? 'action' : 'actions'}
          </Text>
          {info ? <Info title="Scheduled" text={info} /> : null}
          <View style={{ flex: 1 }} />
          {entries.length > 1 && w.user ? (
            <Pressable onPress={() => actions.busy === null && cancelAll()} hitSlop={8}>
              <Text style={{ color: t.danger, fontSize: 13.5, ...font.bold }}>{actions.busy === 'cancel-all' ? 'Cancelling…' : 'Cancel all'}</Text>
            </Pressable>
          ) : null}
        </Row>
        <ScheduledRows
          entries={entries}
          names={w.names}
          runs={runs}
          busy={actions.busy}
          onCancel={cancelOne}
          onRun={actions.run}
          onQueue={actions.queue}
        />
      </Card>
      <ConfirmSheet
        visible={asking !== null}
        options={asking?.options ?? { title: '', confirm: '' }}
        ready={!fee.problem}
        onAnswer={(yes) => {
          if (yes && asking) actions.cancel(asking.entries, asking.label);
          setAsking(null);
        }}>
        <View style={{ borderTopWidth: 1, borderBottomWidth: 1, borderColor: t.border, paddingVertical: 6 }}>
          <FeeRow fee={fee} />
        </View>
      </ConfirmSheet>
    </>
  );
}

