// The wallet home: actions, the coins you follow with the accounts that hold
// each, and what happened. An account opens its own page.
import { OTHER_CHAIN_USER } from '@/core/describe';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { hex } from '@/core/chain/bytes';
import { formatAmount } from '@/core/chain/format';
import { isSignCode } from '@/core/chain/offchain';
import { isPaymentCode } from '@/core/chain/request';
import { useActivity } from '@/core/state/activity';
import { onTheWay, usePending } from '@/core/state/pending';
import { coinGroups, useAccountViews, useDiscoveredCoins } from '@/core/state/coins';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { ActivityList } from '@/ui/ActivityList';
import { PendingList } from '@/ui/PendingList';
import { ScheduledSection, scheduledOf } from '@/ui/Accounts';
import { CoinBadge } from '@/ui/CoinPicker';
import { CoinPanels } from '@/ui/Holdings';
import { Banner, Body, Button, Caption, Card, Dialog, IconCircle, ListItem, Page, Row } from '@/ui/components';
import { Icon } from '@/ui/icons';
import { useModal } from '@/ui/modal';
import { ReceiveDialog, ScanDialog } from '@/ui/Qr';

const SCHEDULED_INFO =
  'Actions of your accounts that run later, in every coin. Check them: anything you did not plan can be cancelled until it runs.';

export function WalletPage() {
  const navigate = useNavigate();
  const modal = useModal();
  const w = useWorkspace();
  const activeSlot = useStore((s) => s.activeSlot);
  const addCoin = useStore((s) => s.addCoin);
  const dismissCoin = useStore((s) => s.dismissCoin);
  const dismissed = useStore((s) => s.settings.dismissed);
  const { views, loading } = useAccountViews(w.coins, w.accounts);
  const [receiveSlot, setReceiveSlot] = useState<number | null>(null);
  const [foundOpen, setFoundOpen] = useState(false);
  const [scanning, setScanning] = useState(false);
  const mine = w.accounts.map((a) => a.account);
  const activity = useActivity(w.info?.status.height, w.scope, { coins: w.coins.map((c) => c.id), identity: true });
  const pending = usePending(w.info?.status.height);
  const excluded = [...w.coins.map((c) => c.hex), ...dismissed, ...(w.info ? [hex(w.info.canonical.registry)] : [])];
  const found = useDiscoveredCoins(w.info?.status.height, w.scope, w.accounts, excluded);
  const selected = w.accounts.find((a) => a.slot === activeSlot) ?? w.accounts[0];
  const groups = coinGroups(w.coins, views);

  return (
    <Page>
      <div className="page-header">
        <div className="stack" style={{ gap: 2 }}>
          <h1 className="page-title">Wallet</h1>
          {w.user ? (
            <Caption>
              {w.user.name} · {w.user.kind === 'identity' ? 'Identity' : 'Key account'}
            </Caption>
          ) : null}
        </div>
        {w.user ? (
          <div className="toolbar">
            <Button title="Send" icon="arrow-up" onClick={() => navigate('/send')} disabled={w.coins.length === 0} />
            <Button title="Receive" kind="ghost" icon="arrow-down" onClick={() => selected && setReceiveSlot(selected.slot)} disabled={!selected} />
            <Button title="Schedule" kind="ghost" icon="time-outline" onClick={() => navigate('/send')} disabled={w.coins.length === 0} />
            <Button title="Scan" kind="ghost" icon="scan-outline" onClick={() => setScanning(true)} />
          </div>
        ) : null}
      </div>

      {!w.user ? (
        <Card>
          <IconCircle name="finger-print-outline" size={48} />
          <span className="heading">Welcome</span>
          <Body muted>An instant spending account, a time-locked savings vault, and guardians who can restore your access.</Body>
          <div>
            <Button title="Get started" icon="person-add-outline" onClick={() => modal.open('/user/new')} />
          </div>
        </Card>
      ) : null}
      {w.otherChain ? <Banner tone="warning">{OTHER_CHAIN_USER}</Banner> : null}
      {w.user?.kind === 'identity' && w.info && !w.otherChain && !w.identity && !w.identityLoading ? (
        <Banner tone="warning">Waiting for the next block to create this identity.</Banner>
      ) : null}

      {w.user ? (
        <>
          {w.coins.length > 0 ? (
            <CoinPanels
              groups={groups}
              accounts={w.accounts}
              loading={loading}
              onOpen={(c, slot) => navigate(`/coin/${c.hex}?slot=${slot}`)}
              onAllCoins={() => navigate('/coins')}
              pendingFor={(c, v) => onTheWay(pending.items, c.hex, hex(v.account.account))}
            />
          ) : (
            <Card>
              <Row>
                <IconCircle name="server-outline" />
                <span className="stack grow" style={{ gap: 2 }}>
                  <span className="sub">{w.coinsLoading ? 'Loading coins…' : 'No coins followed yet'}</span>
                  <Caption>Add the coins you use in Settings.</Caption>
                </span>
                {!w.coinsLoading ? <Button title="Add a coin" kind="secondary" small icon="add" onClick={() => navigate('/settings')} /> : null}
              </Row>
            </Card>
          )}
        </>
      ) : null}

      {found.length > 0 ? (
        <button className="hint-link" onClick={() => setFoundOpen(true)}>
          <Icon name="sparkles-outline" size={14} />
          You also hold {found.length} other coin{found.length === 1 ? '' : 's'}
          <Icon name="chevron-forward" size={14} />
        </button>
      ) : null}

      {w.user ? <ScheduledSection w={w} entries={scheduledOf(w.coins, views)} acting={null} info={SCHEDULED_INFO} /> : null}

      {w.user ? <PendingList items={pending.items} onRetry={pending.retry} onDismiss={pending.dismiss} /> : null}

      {w.user && w.coins.length > 0 ? (
        <>
          <ActivityList activity={activity} coins={w.coinById} names={w.names} mine={mine} />
        </>
      ) : null}

      <ReceiveDialog accounts={w.accounts} slot={receiveSlot} onSlot={setReceiveSlot} onClose={() => setReceiveSlot(null)} />
      <ScanDialog
        open={scanning}
        onClose={() => setScanning(false)}
        title="Scan a code"
        onScan={(text) => {
          // A site's signing request opens for review; a payment code opens Send.
          if (isSignCode(text)) {
            setScanning(false);
            modal.open(`/sign?code=${encodeURIComponent(text)}`);
            return null;
          }
          if (isPaymentCode(text)) {
            setScanning(false);
            navigate(`/send?code=${encodeURIComponent(text)}`);
            return null;
          }
          return 'That is not a Ting code.';
        }}
      />

      <Dialog open={foundOpen} onClose={() => setFoundOpen(false)} title="Coins you hold">
        <Caption>Coins your accounts hold that you do not follow.</Caption>
        <div className="card flush">
          {found.map((f) => (
            <ListItem
              key={f.info.hex}
              left={<CoinBadge coin={f.info} size={36} />}
              title={f.info.config.name}
              subtitle={`${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}`}
              right={
                <Row>
                  <Button
                    title="Follow"
                    kind="secondary"
                    small
                    onClick={() => {
                      addCoin(f.info.hex);
                      if (found.length === 1) setFoundOpen(false);
                    }}
                  />
                  <Button
                    title="Hide"
                    kind="ghost"
                    small
                    onClick={() => {
                      dismissCoin(f.info.hex);
                      if (found.length === 1) setFoundOpen(false);
                    }}
                  />
                </Row>
              }
            />
          ))}
        </div>
      </Dialog>
    </Page>
  );
}
