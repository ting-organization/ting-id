import { useParams } from 'react-router-dom';
import { equal, fromUtf8, hex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatTime } from '@/core/chain/format';
import * as idp from '@/core/chain/identity';
import { ActivityItem, useTransaction } from '@/core/state/chain';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { describeActivity, sentence } from '@/core/describe';
import type { Tone } from '@/platform/ui-types';
import { Banner, Button, Caption, Card, Detail, Dialog, IconCircle, IdChip, Loading, Sub } from '@/ui/components';
import { useModal } from '@/ui/modal';

export function TransactionDialog() {
  const modal = useModal();
  const { id } = useParams<{ id: string }>();
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
  const header: Record<string, { icon: string; label: string; tone: Tone }> = {
    ok: { icon: 'checkmark', label: 'Confirmed', tone: 'success' },
    failed: { icon: 'close', label: receipt?.statusName ?? 'Failed', tone: 'danger' },
    pending: { icon: 'time-outline', label: 'Waiting for a block', tone: 'warning' },
    deferred: { icon: 'hourglass-outline', label: 'Queued on the node', tone: 'accent' },
    unknown: { icon: 'help', label: 'Unknown to the node', tone: 'neutral' },
    loading: { icon: 'ellipsis-horizontal', label: 'Looking it up…', tone: 'neutral' },
  };
  const h = header[state];

  return (
    <Dialog open onClose={modal.close} title="Transaction">
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '8px 0 12px' }}>
        <IconCircle name={h.icon} tone={h.tone} size={72} />
        <span className="heading" style={{ textAlign: 'center' }}>
          {submitted?.summary ?? 'Transaction'}
        </span>
        <span style={{ color: `var(--${h.tone === 'neutral' ? 'muted' : h.tone})`, fontWeight: 600 }}>{h.label}</span>
      </div>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      {state === 'loading' ? <Loading /> : null}
      {state === 'deferred' && deferred ? (
        <Banner tone="info" icon="hourglass-outline">
          Held by the node until {formatTime(deferred.notBefore)}, then included as soon as it succeeds
          {deferred.lastResult ? ` (last attempt: ${deferred.lastResult})` : ''}.
        </Banner>
      ) : null}
      {state === 'failed' && receipt ? <Banner tone="danger">{printable ? fromUtf8(receipt.result) : `failed (${receipt.statusName})`}</Banner> : null}

      <Card flush>
        <Detail label="Transaction" value={<IdChip value={id ?? ''} />} />
        {occurrence ? <Detail label="Block" value={String(occurrence.height)} /> : null}
        {receipt && w.advanced ? <Detail label="Cost" value={<Caption>{receipt.used.map((u) => u.toLocaleString()).join(' / ')} C / S / D / G</Caption>} /> : null}
        {receipt && receipt.status === 0 && receipt.result.length === 32 ? <Detail label="Result" value={<IdChip value={hex(receipt.result)} />} /> : null}
        {receipt && receipt.status === 0 && printable && receipt.result.length !== 32 ? <Detail label="Result" value={<Caption>{fromUtf8(receipt.result)}</Caption>} /> : null}
      </Card>

      {receipt && receipt.events.length > 0 ? (
        <>
          <Sub>What happened</Sub>
          <Card flush>
            {receipt.events.map((e, i) => {
              const item: ActivityItem = { height: occurrence!.height, time: 0, transaction: id ?? '', contract: e.contract };
              if (w.coinById.has(hex(e.contract))) item.coin = coin.decodeCoinEvent(e.payload) ?? undefined;
              else if (w.info && equal(e.contract, w.info.canonical.registry)) item.identity = idp.decodeIdEvent(e.payload) ?? undefined;
              const d = item.coin || item.identity ? describeActivity(item, w.coinById, w.names, mine) : null;
              return (
                <div key={i} className="list-item">
                  <IconCircle name={d?.icon ?? 'ellipse-outline'} tone={d?.tone ?? 'neutral'} />
                  <span className="text">
                    <span style={{ fontWeight: 600 }}>{d?.title ?? `Event from ${hex(e.contract).slice(0, 12)}…`}</span>
                    {d?.detail ? <Caption>{d.detail}</Caption> : null}
                  </span>
                  {d?.amount ? <span style={{ fontWeight: 600, color: d.amountTone ? `var(--${d.amountTone})` : undefined }}>{d.amount}</span> : null}
                </div>
              );
            })}
          </Card>
        </>
      ) : null}
      <Button title="Done" kind="secondary" onClick={modal.close} block />
    </Dialog>
  );
}
