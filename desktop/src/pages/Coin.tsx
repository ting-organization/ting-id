// One account in one coin, as opened from the wallet: what it holds, its
// scheduled actions and activity; the coin itself has its own page.
import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { equal, hex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatAmount } from '@/core/chain/format';
import { useActivity } from '@/core/state/activity';
import { onTheWay, usePending } from '@/core/state/pending';
import { useAccountViews } from '@/core/state/coins';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { relativeDuration } from '@/core/describe';
import { ScheduledSection, scheduledOf } from '@/ui/Accounts';
import { ActivityList } from '@/ui/ActivityList';
import { PendingList } from '@/ui/PendingList';
import { CoinBadge } from '@/ui/CoinPicker';
import { Banner, Button, Caption, Card, Loading, Page, Pill, Row } from '@/ui/components';
import { Icon } from '@/ui/icons';
import { useModal } from '@/ui/modal';
import { ReceiveDialog } from '@/ui/Qr';

export function CoinPage() {
  const navigate = useNavigate();
  const modal = useModal();
  const { id } = useParams<{ id: string }>();
  const [params] = useSearchParams();
  const w = useWorkspace();
  const c = (id && w.coinById.get(id)) || null;
  const setActiveSlot = useStore((s) => s.setActiveSlot);
  const setActiveCoin = useStore((s) => s.setActiveCoin);
  // The account the wallet opened; Spending when the link names none.
  const account = w.accounts.find((a) => a.slot === Number(params.get('slot') ?? 0)) ?? w.accounts[0] ?? null;
  const coins = c ? [c] : [];
  const accounts = account ? [account] : [];
  const { views, loading } = useAccountViews(coins, accounts);
  const view = (c && views.get(c.hex)?.[0]) || null;
  const activity = useActivity(w.info?.status.height, w.scope, { coins: coins.map((x) => x.id), accounts: accounts.map((a) => a.account), identity: false });
  const pending = usePending(w.info?.status.height);
  const [receiving, setReceiving] = useState(false);
  const scheduled = scheduledOf(coins, views);

  const back = (
    <button className="link-button" onClick={() => navigate('/')} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, alignSelf: 'flex-start', marginLeft: -6 }}>
      <Icon name="chevron-forward" size={16} style={{ transform: 'rotate(180deg)' }} />
      Wallet
    </button>
  );

  if (!c || !account) {
    return (
      <Page>
        {back}
        {w.coinsLoading || !w.user ? <Loading /> : <Banner tone="warning">{c ? 'This account is not available.' : 'This coin is not followed any more.'}</Banner>}
      </Page>
    );
  }

  const locked = account.delay > 0;
  // This account's submissions in this coin that no block has included yet.
  const accountHex = hex(account.account);
  const waiting = pending.items.filter((p) => p.tx.coin === c.hex && p.tx.account === accountHex);
  const out = onTheWay(waiting, c.hex, accountHex);
  const available = view ? coin.available(view.record) : 0n;
  const reserved = view?.record.locked ?? 0n;
  const decimals = c.config.decimals;
  const symbol = c.config.symbol;

  // Send and Schedule start from this account.
  const send = () => {
    setActiveCoin(c.hex);
    setActiveSlot(account.slot);
    navigate(`/send?coin=${c.hex}&slot=${account.slot}`);
  };

  return (
    <Page>
      {back}
      <Card className="summary">
        <Row between wrap gap={16}>
          <Row gap={12}>
            <CoinBadge coin={c} size={40} />
            <span className="stack" style={{ gap: 2 }}>
              <Row gap={8}>
                <span className="summary-name">{c.config.name}</span>
                <Pill label={account.label} tone={locked ? 'accent' : 'primary'} small />
              </Row>
              <Caption>{locked ? `withdrawals wait ${relativeDuration(account.delay)}` : 'instant'}</Caption>
            </span>
          </Row>
          <div className="toolbar">
            <Button title="Send" icon="arrow-up" onClick={send} disabled={!w.user} />
            <Button title="Receive" kind="ghost" icon="arrow-down" onClick={() => setReceiving(true)} />
            <Button title="Schedule" kind="ghost" icon="time-outline" onClick={send} disabled={!w.user} />
            {equal(c.state.minter, account.account) ? <Button title="Mint" kind="ghost" icon="add" onClick={() => modal.open(`/mint/${c.hex}`)} /> : null}
            <Button title={`About ${c.config.name}`} kind="ghost" icon="layers-outline" onClick={() => navigate(`/coins/${c.hex}`)} />
          </div>
        </Row>
        <span className="stack" style={{ gap: 2 }}>
          <Caption>Available in {account.label}</Caption>
          <span className="amount">
            {loading && !view ? '…' : formatAmount(available, decimals)} <small>{symbol}</small>
          </span>
          {reserved > 0n || out.count > 0 ? (
            <Caption>
              {reserved > 0n ? `${formatAmount(reserved, decimals)} ${symbol} reserved in ${scheduled.length} scheduled action${scheduled.length === 1 ? '' : 's'}` : ''}
              {reserved > 0n && out.count > 0 ? ' · ' : ''}
              {out.count > 0 ? `${formatAmount(out.amount, decimals)} ${symbol} on the way` : ''}
            </Caption>
          ) : null}
        </span>
      </Card>

      <ScheduledSection w={w} entries={scheduled} acting={{ coin: c, account }} />

      {w.user ? <PendingList items={waiting} onRetry={pending.retry} onDismiss={pending.dismiss} /> : null}

      {w.user ? (
        <>
          <ActivityList activity={activity} coins={w.coinById} names={w.names} mine={accounts.map((a) => a.account)} />
        </>
      ) : null}

      <ReceiveDialog accounts={accounts} slot={receiving ? account.slot : null} onSlot={() => {}} onClose={() => setReceiving(false)} coin={c} />
    </Page>
  );
}
