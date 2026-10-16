// Transactions this computer sent that no block has included yet, and ones
// that failed: where each stands, with Retry and Dismiss where they help.
import { useState } from 'react';
import type { PendingItem } from '@/core/state/pending';
import { describeWatch } from '@/core/describe';
import { Button, Panel, Row } from './components';
import { useModal } from './modal';

const DOT: Record<string, string> = { danger: 'danger', warning: 'warning', success: 'success' };

export function PendingList({
  items,
  onRetry,
  onDismiss,
}: {
  items: PendingItem[];
  onRetry: (id: string) => Promise<string | null>;
  onDismiss: (id: string) => void;
}) {
  const modal = useModal();
  const [busy, setBusy] = useState<string | null>(null);
  if (items.length === 0) return null;
  return (
    <Panel
      title="Waiting for a block"
      count={items.length}
      info="Transactions this computer sent that no block has included yet. If the node loses one, the app sends the same transaction again; it can never run twice.">
      <div className="table-wrap">
        <table className="table">
          <tbody>
            {items.map(({ tx, watch, canRetry }) => {
              const d = describeWatch(watch, tx.at);
              const stuck = watch.kind === 'failed' || watch.kind === 'lost';
              return (
                <tr key={tx.id} className="link" onClick={() => modal.open(`/tx/${tx.id}`)}>
                  <td style={{ width: 8, paddingRight: 0 }}>
                    <span className={`status-dot ${DOT[d.tone] ?? 'warning'}`} style={{ display: 'block' }}></span>
                  </td>
                  <td>{tx.summary}</td>
                  <td className="soft">{d.detail}</td>
                  <td className="r" onClick={(e) => e.stopPropagation()}>
                    {stuck ? (
                      <Row gap={4} style={{ justifyContent: 'flex-end' }}>
                        {canRetry ? (
                          <Button
                            title="Retry"
                            kind="secondary"
                            small
                            plain
                            loading={busy === tx.id}
                            onClick={async () => {
                              setBusy(tx.id);
                              await onRetry(tx.id);
                              setBusy(null);
                            }}
                          />
                        ) : null}
                        <Button title="Dismiss" kind="ghost" small plain onClick={() => onDismiss(tx.id)} />
                      </Row>
                    ) : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
