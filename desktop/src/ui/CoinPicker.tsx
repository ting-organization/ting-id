// Coins as badges, the wallet's coin cards with the accounts under each, and
// a picker of the account to send from.
import * as coin from '@/core/chain/coin';
import { formatAmount } from '@/core/chain/format';
import type { AccountView, CoinGroup, CoinInfo } from '@/core/state/coins';
import { coinColor } from '@/core/state/store';
import { relativeDuration } from '@/core/describe';
import { Caption, Dialog, IconCircle, initialsOf } from './components';
import { Icon } from './icons';

export function CoinBadge({ coin, size = 40 }: { coin: CoinInfo; size?: number }) {
  return (
    <span className="coin-badge" style={{ width: size, height: size, fontSize: size * 0.36, background: coinColor(coin.hex) }}>
      {initialsOf(coin.config.symbol || coin.config.name)}
    </span>
  );
}

/** What an account is: instant, or how long its withdrawals wait. */
export function accountNote(view: AccountView): string {
  return view.account.delay ? `withdrawals wait ${relativeDuration(view.account.delay)}` : 'instant';
}

/** An amount with its symbol in the muted small style. */
export function Amount({ value, coin: c }: { value: bigint; coin: CoinInfo }) {
  return (
    <span className="amount-inline">
      {formatAmount(value, c.config.decimals)} <small>{c.config.symbol}</small>
    </span>
  );
}

/** One account of a coin: its name and lock, what it has available, and what is reserved. */
/** Payments from an account that no block has included yet. */
export interface OnTheWay {
  count: number;
  amount: bigint;
}

function AccountLine({ coin: c, view, selected, onClick, pending }: { coin: CoinInfo; view: AccountView; selected?: boolean; onClick: () => void; pending?: OnTheWay }) {
  const locked = view.account.delay > 0;
  const reserved = view.record.locked;
  return (
    <button className={`list-item clickable${selected ? ' selected' : ''}`} onClick={onClick}>
      <IconCircle name={locked ? 'lock-closed-outline' : 'wallet-outline'} tone={locked ? 'accent' : 'primary'} size={32} />
      <span className="text">
        <span className="title">{view.account.label}</span>
        <Caption>
          {accountNote(view)}
          {reserved > 0n ? ` · ${formatAmount(reserved, c.config.decimals)} ${c.config.symbol} reserved` : ''}
          {pending && pending.count > 0 ? ` · ${formatAmount(pending.amount, c.config.decimals)} ${c.config.symbol} on the way` : ''}
        </Caption>
      </span>
      <Amount value={coin.available(view.record)} coin={c} />
      {selected ? <Icon name="checkmark" size={18} color="var(--primary)" /> : <Icon name="chevron-forward" size={18} color="var(--faint)" />}
    </button>
  );
}

/** A coin's heading: badge, name and symbol, and the total when it lists several accounts. */
function CoinHead({ group }: { group: CoinGroup }) {
  return (
    <div className="coin-group-head">
      <CoinBadge coin={group.coin} size={38} />
      <span className="stack grow" style={{ gap: 0 }}>
        <span className="name">{group.coin.config.name}</span>
        <Caption>{group.coin.config.symbol}</Caption>
      </span>
      {group.views.length > 1 ? <Amount value={group.total} coin={group.coin} /> : null}
    </div>
  );
}

/** The followed coins as cards, each listing the accounts that hold it; an account opens its page. */
export function CoinGroups({
  groups,
  loading,
  onOpen,
  pendingFor,
}: {
  groups: CoinGroup[];
  loading: boolean;
  onOpen: (coin: CoinInfo, slot: number) => void;
  pendingFor?: (coin: CoinInfo, view: AccountView) => OnTheWay;
}) {
  return (
    <div className="coin-grid">
      {groups.map((g) => (
        <div key={g.coin.hex} className="card flush coin-group">
          <CoinHead group={g} />
          {g.views.map((v) => (
            <AccountLine key={v.account.slot} coin={g.coin} view={v} onClick={() => onOpen(g.coin, v.account.slot)} pending={pendingFor?.(g.coin, v)} />
          ))}
          {g.views.length === 0 ? (
            <div className="list-item">
              <Caption>{loading ? 'Loading balances…' : 'No accounts yet'}</Caption>
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

/** Choosing the account to send from: every account of every followed coin, coin by coin. */
export function AccountPickerDialog({
  open,
  onClose,
  groups,
  selected,
  onSelect,
}: {
  open: boolean;
  onClose: () => void;
  groups: CoinGroup[];
  selected: { coin: string; slot: number } | null;
  onSelect: (coin: CoinInfo, slot: number) => void;
}) {
  return (
    <Dialog open={open} onClose={onClose} title="Send from">
      {groups.map((g) => (
        <div key={g.coin.hex} className="card flush coin-group">
          <CoinHead group={{ ...g, views: [] }} />
          {g.views.map((v) => (
            <AccountLine
              key={v.account.slot}
              coin={g.coin}
              view={v}
              selected={selected?.coin === g.coin.hex && selected?.slot === v.account.slot}
              onClick={() => {
                onSelect(g.coin, v.account.slot);
                onClose();
              }}
            />
          ))}
        </div>
      ))}
      {groups.length === 0 ? <Caption>No coins followed yet; add them in Settings.</Caption> : null}
    </Dialog>
  );
}
