import { useEffect, useRef } from 'react';
import { formatTime, formatWhen } from '@/core/chain/format';
import type { Activity } from '@/core/state/activity';
import type { CoinInfo } from '@/core/state/coins';
import { Names, describeActivity } from '@/core/describe';
import { Button, Caption, Empty, Loading, Panel } from './components';
import { useModal } from './modal';

/**
 * The newest activity first, as a table, a page at a time: scrolling to the
 * end of the list shows the next items already found, and once everything
 * found is shown, older blocks are searched on request.
 */
export function ActivityList({ activity, coins, names, mine }: { activity: Activity; coins: Map<string, CoinInfo>; names: Names; mine: Uint8Array[] }) {
  const modal = useModal();
  const { items, loading, searching, hidden, searchedTo, showMore } = activity;
  const end = useRef<HTMLDivElement>(null);
  // The end of the list coming into view shows more, as long as more is found.
  useEffect(() => {
    const node = end.current;
    if (!node || hidden === 0) return;
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && showMore(), { rootMargin: '200px' });
    observer.observe(node);
    return () => observer.disconnect();
  }, [hidden, items.length, showMore]);
  const more =
    hidden > 0 ? (
      <div ref={end} style={{ display: 'flex', flex: 1, justifyContent: 'center' }}>
        <Button title="Show more" kind="ghost" small onClick={showMore} />
      </div>
    ) : searching && !loading ? (
      <>
        <span className="spinner" />
        <Caption>Searching older blocks…</Caption>
      </>
    ) : searchedTo !== null && !loading && items.length > 0 ? (
      <>
        <Button title="Load older activity" kind="ghost" small onClick={activity.searchOlder} />
        <Caption>Searched back to block {searchedTo}</Caption>
      </>
    ) : null;
  return (
    <Panel title="Activity" footer={more}>
      {loading && items.length === 0 ? <Loading /> : null}
      {!loading && items.length === 0 ? (
        searchedTo === null ? (
          <Empty icon="receipt-outline" title="No activity yet" body="Transfers, scheduled actions and identity changes show up here." />
        ) : (
          <Empty icon="receipt-outline" title="No recent activity" body={`Nothing in the blocks since block ${searchedTo}.`} />
        )
      ) : null}
      {items.length > 0 ? (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Type</th>
                <th>Details</th>
                <th className="r">Amount</th>
                <th className="r">Block</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item, i) => {
                const d = describeActivity(item, coins, names, mine);
                return (
                  <tr key={`${item.transaction}-${i}`} className="link" onClick={() => modal.open(`/tx/${item.transaction}`)}>
                    <td className="num soft" style={{ whiteSpace: 'nowrap' }} title={item.time ? formatTime(item.time) : undefined}>
                      {item.time ? formatWhen(item.time) : <span className="quiet">—</span>}
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button
                        className="link-button"
                        style={{ padding: 0, color: 'var(--text)', fontSize: 13.5, fontWeight: 400 }}
                        onClick={(e) => {
                          e.stopPropagation();
                          modal.open(`/tx/${item.transaction}`);
                        }}>
                        {d.title}
                      </button>
                    </td>
                    <td className="soft">{d.detail}</td>
                    <td className="r num" style={{ whiteSpace: 'nowrap', color: d.amountTone ? `var(--${d.amountTone})` : undefined }}>
                      {d.amount ?? <span className="quiet">—</span>}
                    </td>
                    <td className="r num soft">{item.height}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </Panel>
  );
}
