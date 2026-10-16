// One coin as the chain has it: supply, issuance, minter and contract, with
// Follow, and Mint and Move minting when one of your accounts is the minter.
import { useNavigate, useParams } from 'react-router-dom';
import { hex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatAmount } from '@/core/chain/format';
import { knownCoinsFor } from '@/core/config/known-coins';
import { useCoinIndex } from '@/core/state/coinIndex';
import { CoinInfo, useCoinInfos } from '@/core/state/coins';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { relativeDuration } from '@/core/describe';
import { CoinBadge } from '@/ui/CoinPicker';
import { amountOf, capped, circulating, hasMinter, minterAccount, minterName, originalOf, supplyModel } from '@/core/coinFacts';
import { Panel, Banner, Button, Caption, Card, Detail, Help, IdChip, Loading, Page, Pill, Row } from '@/ui/components';
import { Icon } from '@/ui/icons';
import { useConfirm } from '@/ui/confirm';
import { useModal } from '@/ui/modal';

/** Seconds per block the chain aims for. */
const BLOCK_SECONDS = 60;

export function CoinDetailsPage() {
  const navigate = useNavigate();
  const modal = useModal();
  const { id = '' } = useParams<{ id: string }>();
  const w = useWorkspace();
  const followed = useStore((s) => s.settings.coins.includes(id));
  const addCoin = useStore((s) => s.addCoin);
  const removeCoin = useStore((s) => s.removeCoin);
  const chainHex = w.info ? hex(w.info.status.chainId) : null;
  const listedIds = chainHex ? knownCoinsFor(chainHex).map((k) => k.contract) : [];
  const height = w.info?.status.height;
  // Every coin, to tell whether this one copies another.
  const index = useCoinIndex(chainHex, height);
  const { infos, loading } = useCoinInfos([...new Set([...index.coins, id, ...listedIds])]);
  const c = infos.get(id) ?? null;
  const confirm = useConfirm();

  const back = (
    <button className="link-button" onClick={() => navigate('/coins')} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, alignSelf: 'flex-start', marginLeft: -6 }}>
      <Icon name="chevron-forward" size={16} style={{ transform: 'rotate(180deg)' }} />
      Coins
    </button>
  );
  if (!c) {
    return (
      <Page>
        {back}
        {loading ? <Loading /> : <Banner tone="warning">This chain has no such coin, or a block has not included it yet.</Banner>}
      </Page>
    );
  }

  const listed = listedIds.includes(c.hex);
  const copy = originalOf(
    c,
    [...infos.values()].filter((x): x is CoinInfo => !!x),
    listedIds,
  );
  const mine = minterAccount(c, w.accounts);
  const nextReward = height !== undefined ? coin.blockReward(c.config, c.state.issued, BigInt(height + 1)) : 0n;
  const mined = c.config.initialReward > 0n || c.config.tailReward > 0n;
  const left = capped(c) ? c.config.maxSupply - c.state.issued : null;

  return (
    <Page>
      {back}
      {copy ? (
        <Banner tone="danger" icon="warning-outline">
          Not the original: {listedIds.includes(copy.hex) ? 'the listed' : 'an earlier'} coin {copy.config.name} ({copy.config.symbol}) has the same ticker and a
          name like this one. Anyone can create a coin with any name; only the contract id tells coins apart.{' '}
          <button className="link-button" onClick={() => navigate(`/coins/${copy.hex}`)}>
            Open the original
          </button>
        </Banner>
      ) : null}
      <Card className="summary">
        <Row between wrap gap={16}>
          <Row gap={12}>
            <CoinBadge coin={c} size={44} />
            <span className="stack" style={{ gap: 2 }}>
              <Row gap={8} wrap>
                <span className="summary-name">{c.config.name}</span>
                {listed ? <Pill label="listed" tone="success" small /> : null}
                {mine ? <Pill label="you mint" tone="primary" small /> : null}
              </Row>
              <Caption>
                {c.config.symbol} · {supplyModel(c)}
              </Caption>
            </span>
          </Row>
          <div className="toolbar">
            {mine ? <Button title="Mint" icon="add" onClick={() => modal.open(`/mint/${c.hex}`)} /> : null}
            {mine ? <Button title="Move minting" kind="ghost" icon="key-outline" onClick={() => modal.open(`/minter/${c.hex}`)} /> : null}
            {followed ? (
              <Button
                title="Following"
                kind="ghost"
                icon="checkmark"
                onClick={async () => {
                  if (await confirm({ title: `Unfollow ${c.config.name}?`, body: 'It disappears from this wallet. What you hold stays on chain, and you can follow it again from Coins.', confirm: 'Unfollow', back: 'Keep following', danger: true })) removeCoin(c.hex);
                }}
              />
            ) : (
              <Button title="Follow" kind={mine ? 'ghost' : 'primary'} icon="add" onClick={() => addCoin(c.hex)} />
            )}
          </div>
        </Row>
        <span className="stack" style={{ gap: 2 }}>
          <Caption>Circulating</Caption>
          <span className="amount">
            {formatAmount(circulating(c), c.config.decimals)} <small>{c.config.symbol}</small>
          </span>
          <Caption>{capped(c) ? `of at most ${amountOf(c, c.config.maxSupply)}` : 'no supply cap'}</Caption>
        </span>
      </Card>

      <div className="grid-2">
        <div className="col">
          <Panel title="Supply">
            <Detail label="Issued" value={amountOf(c, c.state.issued)} />
            <Detail label="Burned" value={amountOf(c, c.state.burned)} />
            <Detail label="Supply cap" value={capped(c) ? amountOf(c, c.config.maxSupply) : 'none'} />
            {left !== null ? <Detail label="Left to issue" value={amountOf(c, left)} /> : null}
          </Panel>
          <Panel title="Minting" info="The minter is an account. When it belongs to an identity, the right to mint stays with the identity through lost devices and recovery. From a time-locked account every mint waits out its lock and can be cancelled until then.">
            <Detail label="Minter" value={minterName(c, w.accounts, w.names)} />
            {mine ? (
              <Detail label="Mints" value={mine.delay > 0 ? `wait ${relativeDuration(mine.delay)}, can be cancelled` : 'at once'} />
            ) : null}
            {!hasMinter(c) ? (
              <div className="list-item">
                <Caption className="grow">No one can mint {c.config.symbol}: new coins come only from mining rewards, if any.</Caption>
              </div>
            ) : null}
          </Panel>
        </div>
        <div className="col">
          <Panel title="Mining">
            {mined ? (
              <>
                <Detail label="Reward per block now" value={amountOf(c, nextReward)} />
                <Detail
                  label="Halves every"
                  value={c.config.halvingInterval > 0n ? `${formatAmount(c.config.halvingInterval, 0)} blocks · about ${relativeDuration(c.config.halvingInterval * BigInt(BLOCK_SECONDS))}` : 'never'}
                />
                {c.config.tailReward > 0n ? <Detail label="Least reward" value={amountOf(c, c.config.tailReward)} /> : null}
                <Detail label="Last claimed" value={c.state.lastClaimed > c.config.deployHeight ? `block ${c.state.lastClaimed}` : 'never'} />
              </>
            ) : (
              <div className="list-item">
                <Caption className="grow">Mining pays no {c.config.symbol}.</Caption>
              </div>
            )}
          </Panel>
          <Panel title="Coin">
            <Detail label="Decimals" value={String(c.config.decimals)} />
            <Detail label="Created" value={`block ${c.config.deployHeight}`} />
            <Detail label="Contract" value={<IdChip value={c.hex} />} />
            <div className="list-item">
              <Caption className="grow">Runs the standard coin program</Caption>
              <Help text="The app checks the contract's code before it shows a coin: every coin here follows the same audited rules, and only its options differ." />
            </div>
          </Panel>
        </div>
      </div>
    </Page>
  );
}
