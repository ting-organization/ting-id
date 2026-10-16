import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { policyAccount } from '@/core/chain/accounts';
import { hex, isHex, unhex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { policyFor } from '@/core/chain/contracts';
import { formatAmount, nowSeconds, parseAmount, shortId } from '@/core/chain/format';
import * as idp from '@/core/chain/identity';
import { isSignCode } from '@/core/chain/offchain';
import { isPaymentCode, parseRequest } from '@/core/chain/request';
import { queueRun, resolveSigner, submitCoinAction } from '@/core/state/chain';
import { coinGroups, useAccountViews } from '@/core/state/coins';
import { useFee, withFee } from '@/core/state/fees';
import { runTip } from '@/core/state/payer';
import { useContactsHere, useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { lockOf, relativeDuration, runsIn, sentence } from '@/core/describe';
import { AccountPickerDialog, Amount, CoinBadge, accountNote } from '@/ui/CoinPicker';
import { FeeRow } from '@/ui/FeeRow';
import { Avatar, Banner, Body, Button, Caption, Card, Dialog, Field, ListItem, Page, Panel, Row, Segmented } from '@/ui/components';
import { Icon } from '@/ui/icons';
import { useModal } from '@/ui/modal';
import { ScanDialog } from '@/ui/Qr';
import { useConfirm } from '@/ui/confirm';

const SCHEDULE = [
  { value: 'now', label: 'Now', seconds: 0 },
  { value: '1h', label: '+1 hour', seconds: 3600 },
  { value: '1d', label: '+1 day', seconds: 86_400 },
  { value: '7d', label: '+7 days', seconds: 604_800 },
] as const;

export function SendPage() {
  const modal = useModal();
  const [params, setParams] = useSearchParams();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const contacts = useContactsHere();
  const activeSlot = useStore((s) => s.activeSlot);
  const setActiveSlot = useStore((s) => s.setActiveSlot);
  const activeCoin = useStore((s) => s.activeCoin);
  const setActiveCoin = useStore((s) => s.setActiveCoin);
  const addSubmitted = useStore((s) => s.addSubmitted);
  // The coin and account opened from, else the ones used last, else the first followed coin's Spending.
  const coinParam = params.get('coin');
  const slotParam = params.get('slot');
  const selectedCoin = (coinParam && w.coinById.get(coinParam)) || (activeCoin && w.coinById.get(activeCoin)) || w.coins[0] || null;
  const { views: allViews } = useAccountViews(w.coins, w.accounts);
  const views = (selectedCoin && allViews.get(selectedCoin.hex)) || [];
  const [fromOpen, setFromOpen] = useState(false);
  const [to, setTo] = useState('');
  const [toLabel, setToLabel] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const [schedule, setSchedule] = useState<(typeof SCHEDULE)[number]['value']>('now');
  const [picker, setPicker] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [codeNotice, setCodeNotice] = useState<string | null>(null);
  /** The coin a payment code asked for: nothing is sent in another one. */
  const [codeCoin, setCodeCoin] = useState<string | null>(null);
  const forgetCode = () => {
    setCodeNotice(null);
    setCodeCoin(null);
  };
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const from = views.find((v) => v.account.slot === (slotParam !== null ? Number(slotParam) : activeSlot)) ?? views[0];
  // The fee goes in the fee coin from Settings. A time-locked account pays none
  // itself: nothing leaves it at once (the coin reverts `delayed fee`), so an
  // instant account pays for it, Spending by default.
  const delayed = (from?.account.delay ?? 0) > 0;
  const fee = useFee(w, selectedCoin && from ? { coin: selectedCoin, account: from.account } : null);
  const decimals = selectedCoin?.config.decimals ?? 0;
  const symbol = selectedCoin?.config.symbol ?? '';

  const recipients = (() => {
    const out: { label: string; sub: string; account: string; color: string }[] = [];
    for (const u of users) {
      if (u.kind === 'identity' && u.identityId && u.registry) {
        const slots = (u.creation ? idp.decodeCreation(unhex(u.creation))?.slots : null) ?? idp.DEFAULT_SLOTS;
        slots.forEach((delay, slot) => {
          const account = hex(idp.account(unhex(u.registry!), unhex(u.identityId!), slot));
          if (u.id === w.user?.id && slot === from?.account.slot) return;
          out.push({
            label: `${u.name} · ${slot === 0 ? 'Spending' : slot === 1 ? 'Savings' : `Account ${slot}`}`,
            sub: delay ? lockOf(delay) : 'instant',
            account,
            color: u.color,
          });
        });
      } else if (u.kind === 'key' && w.info) {
        const key = w.keys.find((k) => k.id === u.keyId);
        if (key && u.id !== w.user?.id) {
          const account = hex(policyAccount(policyFor(w.info.canonical, key.scheme), unhex(key.pub)));
          out.push({ label: u.name, sub: 'simple account', account, color: u.color });
        }
      }
    }
    for (const c of contacts) out.push({ label: c.name, sub: 'contact', account: c.account, color: '#64748B' });
    return out;
  })();

  // A payment code, scanned or pasted, fills the recipient and, when it names
  // a followed coin, picks that coin. Returns what is wrong with it, if anything.
  const applyCode = (text: string): string | null => {
    const request = parseRequest(text);
    if (!request) return 'This code does not hold a Ting account.';
    setTo(request.account);
    setToLabel(recipients.find((r) => r.account === request.account)?.label ?? null);
    setCodeNotice(null);
    setCodeCoin(request.coin ?? null);
    if (request.coin && request.coin !== selectedCoin?.hex) {
      if (w.coinById.get(request.coin)) {
        setActiveCoin(request.coin);
        setParams({ coin: request.coin, slot: String(from?.account.slot ?? 0) });
      } else {
        setCodeNotice(`The code asks for coin ${shortId(request.coin)}, which you do not follow. Follow it in Settings to send it.`);
      }
    }
    return null;
  };

  const scanned = (text: string): string | null => {
    // A site's signing request scanned here opens for review instead.
    if (isSignCode(text)) {
      setScanning(false);
      modal.open(`/sign?code=${encodeURIComponent(text)}`);
      return null;
    }
    const problem = applyCode(text);
    if (!problem) setScanning(false);
    return problem;
  };

  // A payment code scanned elsewhere arrives as `?code=`.
  const codeParam = params.get('code');
  const appliedCode = useRef<string | null>(null);
  const confirm = useConfirm();
  useEffect(() => {
    if (!codeParam || appliedCode.current === codeParam) return;
    appliedCode.current = codeParam;
    const code = codeParam;
    // What was typed stays unless the person takes the new code instead.
    const typed = amount.trim() !== '' || to.trim() !== '';
    (typed
      ? confirm({ title: 'Use the new payment code?', body: 'It replaces the recipient and amount you entered.', confirm: 'Use the code', back: 'Keep what I entered' })
      : Promise.resolve(true)
    ).then((yes) => {
      if (!yes) return;
      setAmount('');
      applyCode(code);
    });
  }, [codeParam]); // eslint-disable-line react-hooks/exhaustive-deps

  const parsed = (() => {
    try {
      return amount ? parseAmount(amount, decimals) : 0n;
    } catch {
      return null;
    }
  })();
  // The fee the action carries itself, when its own account pays in this coin.
  const ownFee = fee.own ? fee.fee : 0n;
  const delaySeconds = SCHEDULE.find((s) => s.value === schedule)!.seconds;
  const willSchedule = delaySeconds > 0 || delayed;
  const availableNow = from ? coin.available(from.record) : 0n;
  // A scheduled action pays the fee's amount again when it runs, reserved until then.
  const runFee = willSchedule && selectedCoin ? runTip(fee.amount, selectedCoin) : 0n;
  const needed = (parsed ?? 0n) + ownFee + runFee;
  const problems: string[] = [];
  if (!selectedCoin) problems.push('follow a coin in Settings');
  if (!from) problems.push('no account to send from');
  if (to && !isHex(to, 32)) problems.push('the recipient must be a 64-digit hex account');
  if (amount && parsed === null) problems.push(`enter an amount with at most ${decimals} decimals`);
  if (parsed !== null && parsed === 0n && amount) problems.push('amount must be above zero');
  if (fee.problem) problems.push(fee.problem);
  if (from && needed > availableNow) problems.push(`only ${formatAmount(availableNow, decimals)} ${symbol} available`);
  const coinMismatch = !!codeCoin && codeCoin !== selectedCoin?.hex;
  const ready = problems.length === 0 && to && parsed && parsed > 0n && !coinMismatch;

  const send = async () => {
    if (!ready || !w.user || !w.info || !selectedCoin || !from) return;
    setBusy(true);
    setError(null);
    try {
      const signer = await resolveSigner(w.user, from.account.slot, w.info.canonical, w.keys, w.identity);
      const action: coin.Action = {
        effect: { kind: 'transfer', to: unhex(to), amount: parsed! },
        timing: {
          fee: 0n,
          tip: runFee,
          notBefore: delaySeconds ? BigInt(nowSeconds() + delaySeconds) : 0n,
          validUntil: 0n,
          within: null,
        },
      };
      const payer = await fee.resolve();
      const r = await submitCoinAction(w.node, w.info.status.chainId, selectedCoin.id, signer, action, undefined, payer);
      let queued = false;
      if (willSchedule) {
        // The node runs it when due: hand it the run now, timed for the later of
        // the chosen time and the account's own delay.
        const due = nowSeconds() + Math.max(delaySeconds, from.account.delay);
        queued = await queueRun(w.node, selectedCoin.id, r.account, r.nonce, due)
          .then(() => true)
          .catch(() => false);
      }
      const summary = `${willSchedule ? 'Schedule' : 'Send'} ${formatAmount(parsed!, decimals)} ${symbol} to ${toLabel ?? to.slice(0, 10)}${queued ? ' (run queued)' : ''}`;
      addSubmitted({
        id: r.txId,
        at: nowSeconds(),
        summary: withFee(summary, fee),
        userId: w.user.id,
        coin: selectedCoin.hex,
        account: hex(from.account.account),
        amount: needed.toString(),
      });
      setAmount('');
      modal.open(`/tx/${r.txId}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Page title="Send">
      {!w.user ? <Banner>Create a user first.</Banner> : null}
      <AccountPickerDialog
        open={fromOpen}
        onClose={() => setFromOpen(false)}
        groups={coinGroups(w.coins, allViews, true)}
        selected={selectedCoin && from ? { coin: selectedCoin.hex, slot: from.account.slot } : null}
        onSelect={(c, slot) => {
          setActiveCoin(c.hex);
          setActiveSlot(slot);
          setParams({ coin: c.hex, slot: String(slot) });
          forgetCode();
        }}
      />
      <div className="grid-2">
        <div className="col">
          {w.user && selectedCoin ? (
            <button className="card from-card" onClick={() => setFromOpen(true)} title="Choose the coin and account to send from">
              <CoinBadge coin={selectedCoin} size={40} />
              <span className="stack grow" style={{ gap: 1 }}>
                <Caption>From</Caption>
                <span className="title">
                  {w.user.name} · {from?.account.label ?? '…'}
                </span>
                {from ? <Caption>{accountNote(from)}</Caption> : null}
              </span>
              {from ? (
                <span className="stack" style={{ gap: 1, alignItems: 'flex-end' }}>
                  <Amount value={availableNow} coin={selectedCoin} />
                  <Caption>available</Caption>
                </span>
              ) : null}
              <Icon name="chevron-down" size={18} color="var(--faint)" />
            </button>
          ) : null}
          {from?.account.delay ? (
            <Caption>
              Sending from {from.account.label} is scheduled {relativeDuration(from.account.delay)} ahead and can be cancelled until then; its fee comes from an
              instant account.
            </Caption>
          ) : null}
          <Card>
            <Field
              label="To"
              value={to}
              onChangeText={(v) => {
                if (isPaymentCode(v) && applyCode(v) === null) return;
                setTo(v.trim());
                setToLabel(null);
                forgetCode();
              }}
              placeholder="account (64 hex digits) or payment code"
              hint={toLabel ?? undefined}
              mono
            />
            <Row>
              <Button title="Choose a recipient" kind="ghost" small icon="people-outline" onClick={() => setPicker(true)} />
              <Button title="Scan QR code" kind="ghost" small icon="scan-outline" onClick={() => setScanning(true)} />
            </Row>
            <Field label={`Amount (${symbol})`} value={amount} onChangeText={setAmount} placeholder="0.00" />
            <span className="field-label">When</span>
            <Segmented options={SCHEDULE.map((s) => ({ value: s.value, label: s.label }))} value={schedule} onChange={setSchedule} />
            {w.user ? <FeeRow fee={fee} run={selectedCoin && runFee > 0n ? { amount: runFee, coin: selectedCoin } : undefined} /> : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', paddingTop: 4 }}>
              <Button title={willSchedule ? 'Schedule' : 'Send'} icon={willSchedule ? 'time-outline' : 'arrow-up'} onClick={send} disabled={!ready} loading={busy} />
            </div>
          </Card>
        </div>
        <div className="col">
          {codeNotice ? (
            <Banner tone="warning">{codeNotice}</Banner>
          ) : coinMismatch ? (
            <Banner tone="warning">The payment code asks for {w.coinById.get(codeCoin!)?.config.symbol ?? shortId(codeCoin!)}; choose that coin to send.</Banner>
          ) : null}
          {problems.length > 0 && (amount || to) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
          {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
          {ready ? (
            <Panel title="Review">
              <div className="stack" style={{ padding: '14px 18px' }}>
              <Body>
                {formatAmount(parsed!, decimals)} {symbol} to {toLabel ?? 'the account'}
              </Body>
              <Caption className="num" style={{ wordBreak: 'break-all' }}>
                {to}
              </Caption>
              <Caption>
                {willSchedule
                  ? `${runsIn(delaySeconds, from?.account.delay ?? 0)}; ${formatAmount((parsed ?? 0n) + runFee, decimals)} ${symbol} is reserved until then.`
                  : 'Executes as soon as a block includes it.'}
                {fee.summary ? ` ${fee.summary}.` : ''}
              </Caption>
              </div>
            </Panel>
          ) : null}
        </div>
      </div>

      <ScanDialog open={scanning} onClose={() => setScanning(false)} onScan={scanned} />
      <Dialog open={picker} onClose={() => setPicker(false)} title="Recipient">
        {recipients.length === 0 ? <Caption>No contacts yet.</Caption> : null}
        <div className="card flush">
          {recipients.map((r) => (
            <ListItem
              key={r.account + r.label}
              left={<Avatar name={r.label} color={r.color} size={36} />}
              title={r.label}
              subtitle={r.sub}
              onClick={() => {
                setTo(r.account);
                setToLabel(r.label);
                forgetCode();
                setPicker(false);
              }}
            />
          ))}
        </div>
      </Dialog>
    </Page>
  );
}
