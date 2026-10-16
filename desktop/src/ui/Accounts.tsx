// Scheduled actions of the user's accounts: what is waiting to run, who runs
// it, and the buttons to run, queue or cancel it, as rows and as a whole
// section with the fee its cancels pay.
import * as coin from '@/core/chain/coin';
import { formatAmount, nowSeconds, relativeTime } from '@/core/chain/format';
import { RunRef, RunStatus, runKey, useRunStatuses } from '@/core/state/chain';
import type { AccountView, CoinInfo } from '@/core/state/coins';
import { Acting, useFee } from '@/core/state/fees';
import { useScheduledActions } from '@/core/state/scheduled';
import type { useWorkspace } from '@/core/state/workspace';
import { describeEffect, Names, sentence } from '@/core/describe';
import { Banner, Button, Panel, Row } from './components';
import { FeeRow } from './FeeRow';
import { useConfirm } from './confirm';
import { useModal } from './modal';

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

export function scheduledKey(e: ScheduledEntry): string {
  return `${e.coin.hex}-${e.view.account.slot}-${e.sequence}`;
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

export function runRefOf(e: ScheduledEntry): RunRef {
  return { coinId: e.coin.id, account: e.view.account.account, sequence: e.sequence };
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
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Action</th>
            <th>From</th>
            <th>Runs</th>
            <th>Run</th>
            <th aria-label="Actions"></th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => {
            const due = Number(e.record.executeAt) <= nowSeconds();
            const key = scheduledKey(e);
            const status = runs.get(runKey(runRefOf(e))) ?? null;
            const handled = status === 'queued' || status === 'pending';
            return (
              <tr key={key}>
                <td>
                  {describeEffect(e.record.effect, e.coin, names)}
                  {e.record.tip > 0n ? <span className="soft">{` · run fee ${formatAmount(e.record.tip, e.coin.config.decimals)} ${e.coin.config.symbol}`}</span> : null}
                </td>
                <td>{e.view.account.label}</td>
                <td>{due ? 'due now' : relativeTime(e.record.executeAt)}</td>
                <td className="soft">{runNote(status, due)}</td>
                <td className="r">
                  <Row gap={4} style={{ justifyContent: 'flex-end' }}>
                    {due && !handled ? <Button title="Run now" kind="secondary" small plain loading={busy === `run-${key}`} onClick={() => onRun(e)} /> : null}
                    {!due && !handled ? <Button title="Run when due" kind="secondary" small plain loading={busy === `queue-${key}`} onClick={() => onQueue(e)} /> : null}
                    <Button title="Cancel" kind="danger" small plain loading={busy === `cancel-${key}`} onClick={() => onCancel(e)} />
                  </Row>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The scheduled actions `entries` with Cancel, Run now and Run when due, Cancel
 * all, and the fee the cancels pay. `acting`: the coin and account of an
 * account page; none on the wallet, which lists every account's.
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
  const modal = useModal();
  const fee = useFee(w, acting);
  const runs = useRunStatuses(entries.map(runRefOf));
  const actions = useScheduledActions(w, fee, (id) => modal.open(`/tx/${id}`));
  const confirm = useConfirm();
  if (entries.length === 0) return null;
  const cancelOne = async (e: ScheduledEntry) => {
    const yes = await confirm({
      title: 'Cancel this scheduled action?',
      body: `${describeEffect(e.record.effect, e.coin, w.names)}, from ${e.view.account.label}. It will not run.`,
      confirm: 'Cancel action',
      back: 'Keep it',
      danger: true,
    });
    if (yes) actions.cancel([e], `cancel-${scheduledKey(e)}`);
  };
  const cancelAll = async () => {
    const yes = await confirm({
      title: `Cancel all ${entries.length} scheduled actions?`,
      body: 'None of them will run.',
      confirm: 'Cancel all',
      back: 'Keep them',
      danger: true,
    });
    if (yes) actions.cancel(entries, 'cancel-all');
  };
  return (
    <Panel
      title="Scheduled"
      count={entries.length}
      info={info}
      attention
      actions={
        entries.length > 1 && w.user ? (
          <Button title="Cancel all" kind="danger" small loading={actions.busy === 'cancel-all'} onClick={() => actions.busy === null && cancelAll()} />
        ) : null
      }
      footer={
        w.user ? (
          <div style={{ flex: 1 }}>
            <FeeRow fee={fee} />
          </div>
        ) : null
      }>
      {actions.error ? (
        <div style={{ padding: '10px 18px 0' }}>
          <Banner tone="danger">{sentence(actions.error)}</Banner>
        </div>
      ) : null}
      <ScheduledRows
        entries={entries}
        names={w.names}
        runs={runs}
        busy={actions.busy}
        onCancel={cancelOne}
        onRun={actions.run}
        onQueue={actions.queue}
      />
    </Panel>
  );
}

