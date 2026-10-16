// The wallet's coins: a panel each, side by side while they fit, with the
// coin's total in the header and a row per account that holds it. Reserved
// and on-the-way columns appear only for a coin that has some. An account
// opens its page.
import { equal } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatAmount } from '@/core/chain/format';
import type { UserAccount } from '@/core/state/chain';
import type { AccountView, CoinGroup, CoinInfo } from '@/core/state/coins';
import { relativeDuration } from '@/core/describe';
import { CoinBadge, OnTheWay } from './CoinPicker';
import { Panel, SectionHeader } from './components';
import { Icon } from './icons';

function Figure({ value, c }: { value: bigint; c: CoinInfo }) {
  return value > 0n ? <>{formatAmount(value, c.config.decimals)}</> : <span className="quiet">—</span>;
}

function CoinPanel({
  group,
  minting,
  loading,
  onOpen,
  pendingFor,
}: {
  group: CoinGroup;
  minting: boolean;
  loading: boolean;
  onOpen: (coin: CoinInfo, slot: number) => void;
  pendingFor: (coin: CoinInfo, view: AccountView) => OnTheWay;
}) {
  const c = group.coin;
  const rows = group.views.map((v) => ({ view: v, onTheWay: pendingFor(c, v).amount }));
  const showReserved = rows.some((r) => r.view.record.locked > 0n);
  const showOnTheWay = rows.some((r) => r.onTheWay > 0n);
  return (
    <Panel
      title={
        <span className="row" style={{ gap: 10 }}>
          <CoinBadge coin={c} size={22} />
          {c.config.name}
          <span className="quiet" style={{ fontWeight: 400 }}>
            {c.config.symbol}
          </span>
          {minting ? <span className="chip primary">you mint</span> : null}
        </span>
      }
      actions={
        <span className="total num">
          {formatAmount(group.total, c.config.decimals)} <span className="quiet">{c.config.symbol}</span>
        </span>
      }>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Account</th>
              <th className="r">Available</th>
              {showReserved ? <th className="r">Reserved</th> : null}
              {showOnTheWay ? <th className="r">On the way</th> : null}
              <th aria-label="Open" style={{ width: 20 }}></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ view: v, onTheWay }) => {
              const locked = v.account.delay > 0;
              const open = () => onOpen(c, v.account.slot);
              return (
                <tr key={v.account.slot} className="link" onClick={open}>
                  <td>
                    <span className="row" style={{ gap: 8 }}>
                      <button
                        className="link-button"
                        style={{ padding: 0, color: 'var(--text)', fontSize: 13.5 }}
                        onClick={(e) => {
                          e.stopPropagation();
                          open();
                        }}>
                        {v.account.label}
                      </button>
                      {locked ? (
                        <span className="chip">
                          <Icon name="lock-closed-outline" size={11} />
                          {relativeDuration(v.account.delay)}
                        </span>
                      ) : (
                        <span className="quiet">instant</span>
                      )}
                    </span>
                  </td>
                  <td className="r num">{formatAmount(coin.available(v.record), c.config.decimals)}</td>
                  {showReserved ? (
                    <td className="r num">
                      <Figure value={v.record.locked} c={c} />
                    </td>
                  ) : null}
                  {showOnTheWay ? (
                    <td className="r num">
                      <Figure value={onTheWay} c={c} />
                    </td>
                  ) : null}
                  <td className="quiet">
                    <Icon name="chevron-forward" size={15} />
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 ? (
              <tr>
                <td className="soft" colSpan={3}>
                  {loading ? 'Loading balances…' : 'No accounts yet'}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}

export function CoinPanels({
  groups,
  accounts,
  loading,
  onOpen,
  onAllCoins,
  pendingFor,
}: {
  groups: CoinGroup[];
  accounts: UserAccount[];
  loading: boolean;
  onOpen: (coin: CoinInfo, slot: number) => void;
  onAllCoins: () => void;
  pendingFor: (coin: CoinInfo, view: AccountView) => OnTheWay;
}) {
  return (
    <>
      <SectionHeader title="Coins" action="All coins" onAction={onAllCoins} />
      <div className="coin-panels">
        {groups.map((g) => (
          <CoinPanel
            key={g.coin.hex}
            group={g}
            minting={accounts.some((a) => equal(a.account, g.coin.state.minter))}
            loading={loading}
            onOpen={onOpen}
            pendingFor={pendingFor}
          />
        ))}
      </div>
    </>
  );
}
