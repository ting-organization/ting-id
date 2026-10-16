// Creating a coin: a name, a symbol and one of three ways new coins come
// about. A fixed supply goes to one of your accounts; a mined coin pays block
// rewards that halve; a minted coin lets one of your accounts mint, Savings by
// default so that every mint waits out its lock and can be cancelled.
import { useState } from 'react';
import { hex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatAmount, nowSeconds, parseAmount } from '@/core/chain/format';
import { deployCoin } from '@/core/state/chain';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { relativeDuration, sentence } from '@/core/describe';
import { AccountChoice } from '@/ui/AccountChoice';
import { Banner, Body, Button, Caption, Card, Dialog, Field, Row, Segmented, Sub } from '@/ui/components';
import { useModal } from '@/ui/modal';

type Kind = 'fixed' | 'mined' | 'minted';

const KINDS: { value: Kind; label: string }[] = [
  { value: 'fixed', label: 'Fixed supply' },
  { value: 'mined', label: 'Mined' },
  { value: 'minted', label: 'You mint' },
];

/** Seconds per block the chain aims for. */
const BLOCK_SECONDS = 60;

function amount(text: string, decimals: number | null): bigint | null {
  if (decimals === null) return null;
  try {
    return parseAmount(text.trim(), decimals);
  } catch {
    return null;
  }
}

export function CreateCoinDialog() {
  const modal = useModal();
  const w = useWorkspace();
  const advanced = useStore((s) => s.settings.advanced);
  const addCoin = useStore((s) => s.addCoin);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const savings = w.accounts.find((a) => a.delay > 0) ?? null;
  const [name, setName] = useState('');
  const [symbol, setSymbol] = useState('');
  const [decimalsText, setDecimalsText] = useState('8');
  const [kind, setKind] = useState<Kind>('fixed');
  const [supply, setSupply] = useState('');
  const [toSlot, setToSlot] = useState(0);
  const [reward, setReward] = useState('');
  const [halving, setHalving] = useState('210000');
  const [tail, setTail] = useState('');
  const [minterSlot, setMinterSlot] = useState<number | null>(null);
  const [cap, setCap] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Savings unless the user picks another: a stolen key cannot mint at once.
  const minter = w.accounts.find((a) => a.slot === (minterSlot ?? savings?.slot ?? 0)) ?? w.accounts[0] ?? null;
  const to = w.accounts.find((a) => a.slot === toSlot) ?? w.accounts[0] ?? null;
  const decimals = /^\d{1,2}$/.test(decimalsText.trim()) && Number(decimalsText) <= 18 ? Number(decimalsText) : null;
  const problems: string[] = [];
  if (decimals === null) problems.push('decimals must be a whole number from 0 to 18');

  const options = ((): coin.CoinOptions | null => {
    if (decimals === null || !minter || !to) return null;
    const base = { name: name.trim(), symbol: symbol.trim(), decimals, initialReward: 0n, halvingInterval: 0n, tailReward: 0n, minter: coin.ZERO_ACCOUNT, allocations: [] };
    if (kind === 'fixed') {
      const total = amount(supply, decimals);
      if (supply && total === null) problems.push(`enter the supply with at most ${decimals} decimals`);
      if (!total) return null;
      return { ...base, maxSupply: total, allocations: [{ account: to.account, amount: total }] };
    }
    if (kind === 'mined') {
      const r = amount(reward, decimals);
      const t = advanced && tail ? amount(tail, decimals) : 0n;
      const n = /^\d+$/.test(halving.trim()) ? BigInt(halving.trim()) : null;
      if (reward && r === null) problems.push(`enter the reward with at most ${decimals} decimals`);
      if (t === null) problems.push(`enter the least reward with at most ${decimals} decimals`);
      if (n === null) problems.push('the halving interval is a whole number of blocks');
      if (!r || t === null || n === null) return null;
      // Halving rewards add up to just under twice the first era: the cap. A
      // constant reward or a least reward keeps paying, so those have none.
      const maxSupply = n > 0n && t === 0n ? 2n * r * n : coin.UNCAPPED;
      if (maxSupply > coin.UNCAPPED) {
        problems.push('the reward is too large');
        return null;
      }
      return { ...base, maxSupply, initialReward: r, halvingInterval: n, tailReward: t };
    }
    const c = cap.trim() ? amount(cap, decimals) : coin.UNCAPPED;
    if (c === null) problems.push(`enter the cap with at most ${decimals} decimals`);
    if (!c) return null;
    return { ...base, maxSupply: c, minter: minter.account };
  })();
  const invalid = options ? coin.deployProblem(options) : null;
  if (invalid && name.trim() && symbol.trim()) problems.push(invalid);
  const ready = !!options && !invalid && !!w.user && !!w.info;

  const create = async () => {
    if (!ready || !w.info || !w.user) return;
    setBusy(true);
    setError(null);
    try {
      const r = await deployCoin(w.node, w.info.status.chainId, options);
      addCoin(hex(r.coinId));
      addSubmitted({ id: r.txId, at: nowSeconds(), summary: `Create ${options.name} (${options.symbol})`, userId: w.user.id });
      modal.open(`/tx/${r.txId}`, true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const fmt = (v: bigint) => (decimals === null ? '' : `${formatAmount(v, decimals)} ${symbol.trim()}`);
  const review = (() => {
    if (!options) return null;
    if (kind === 'fixed') return `${fmt(options.maxSupply)} go to your ${to?.label}. No one can ever make more.`;
    if (kind === 'mined') {
      const era = options.halvingInterval > 0n ? `, halving every ${formatAmount(options.halvingInterval, 0)} blocks (about ${relativeDuration(options.halvingInterval * BigInt(BLOCK_SECONDS))})` : ', the same in every block';
      const least = options.tailReward > 0n ? ` and never below ${fmt(options.tailReward)}` : '';
      const capText = options.maxSupply === coin.UNCAPPED ? 'no cap' : `at most ${fmt(options.maxSupply)} in all`;
      return `Miners earn ${fmt(options.initialReward)} per block${era}${least}; ${capText}.`;
    }
    const capText = options.maxSupply === coin.UNCAPPED ? 'no cap' : `at most ${fmt(options.maxSupply)} in all`;
    const wait = minter && minter.delay > 0 ? `; each mint waits ${relativeDuration(minter.delay)} and can be cancelled until then` : '; mints happen at once';
    return `Your ${minter?.label} mints${wait}. ${capText[0].toUpperCase()}${capText.slice(1)}.`;
  })();

  return (
    <Dialog open onClose={modal.close} busy={busy} title="Create a coin" size="lg">
      <Row gap={12} wrap>
        <div style={{ flex: 2, minWidth: 200 }}>
          <Field label="Name" value={name} onChangeText={setName} placeholder="Garden Tokens" autoFocus />
        </div>
        <div style={{ flex: 1, minWidth: 110 }}>
          <Field label="Symbol" value={symbol} onChangeText={setSymbol} placeholder="GRDN" />
        </div>
        <div style={{ width: 110 }}>
          <Field label="Decimals" value={decimalsText} onChangeText={setDecimalsText} />
        </div>
      </Row>
      <span className="field-label">New coins come from</span>
      <Segmented options={KINDS} value={kind} onChange={setKind} />

      {kind === 'fixed' ? (
        <>
          <Field label={`Total supply${symbol.trim() ? ` (${symbol.trim()})` : ''}`} value={supply} onChangeText={setSupply} placeholder="1000000" />
          <span className="field-label">Goes to</span>
          <AccountChoice accounts={w.accounts} slot={to?.slot ?? null} onSlot={setToSlot} />
        </>
      ) : null}

      {kind === 'mined' ? (
        <>
          <Row gap={12} wrap>
            <div style={{ flex: 1, minWidth: 180 }}>
              <Field label={`Reward per block${symbol.trim() ? ` (${symbol.trim()})` : ''}`} value={reward} onChangeText={setReward} placeholder="50" />
            </div>
            <div style={{ flex: 1, minWidth: 180 }}>
              <Field label="Halves every (blocks)" value={halving} onChangeText={setHalving} hint="0 for a reward that never changes" />
            </div>
          </Row>
          {advanced ? <Field label="Least reward (optional)" value={tail} onChangeText={setTail} placeholder="0" hint="Paid forever once the halvings go below it; the coin then has no cap." /> : null}
          <Caption>Miners earn the reward once they add the coin to what they claim.</Caption>
        </>
      ) : null}

      {kind === 'minted' ? (
        <>
          <span className="field-label">Who mints</span>
          <AccountChoice
            accounts={w.accounts}
            slot={minter?.slot ?? null}
            onSlot={setMinterSlot}
            badge={savings ? { slot: savings.slot, label: 'recommended' } : undefined}
            note={(a) => (a.delay > 0 ? `each mint waits ${relativeDuration(a.delay)} and can be cancelled: a stolen key cannot mint at once` : 'mints at once')}
          />
          <Field label="Supply cap (optional)" value={cap} onChangeText={setCap} placeholder="none" hint="Leave empty for no cap. Minting can be handed to another account or given up later." />
        </>
      ) : null}

      {problems.length > 0 && (name || symbol || supply || reward || cap) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      {ready && review ? (
        <Card tone="alt">
          <Sub>
            {options.name} · {options.symbol}
          </Sub>
          <Body>{review}</Body>
          <Caption>Its name, symbol and supply rules can never change. Creating a coin pays no fee; it waits for room in a block.</Caption>
        </Card>
      ) : null}
      <Row>
        <Button title="Create coin" onClick={create} disabled={!ready} loading={busy} />
        <Button title="Cancel" kind="ghost" onClick={modal.close} />
      </Row>
    </Dialog>
  );
}
