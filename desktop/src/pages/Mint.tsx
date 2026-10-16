// Minting from the account that holds the coin's minting right. From a
// time-locked account the mint is scheduled: it waits out the lock, can be
// cancelled on the account's page until then, and the node runs it when due.
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { hex, isHex, unhex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { formatAmount, nowSeconds, parseAmount } from '@/core/chain/format';
import { isPaymentCode, parseRequest } from '@/core/chain/request';
import { queueRun, resolveSigner, submitCoinAction } from '@/core/state/chain';
import { useCoinInfos } from '@/core/state/coins';
import { useFee, useRunFee, withFee } from '@/core/state/fees';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { relativeDuration, sentence } from '@/core/describe';
import { AccountChoice } from '@/ui/AccountChoice';
import { amountOf, capped, minterAccount } from '@/core/coinFacts';
import { FeeRow } from '@/ui/FeeRow';
import { Banner, Button, Caption, Dialog, Field, Loading, Row } from '@/ui/components';
import { useModal } from '@/ui/modal';

export function MintDialog() {
  const modal = useModal();
  const { id = '' } = useParams<{ id: string }>();
  const w = useWorkspace();
  const addSubmitted = useStore((s) => s.addSubmitted);
  const { infos } = useCoinInfos([id]);
  const c = infos.get(id) ?? null;
  const minter = c ? minterAccount(c, w.accounts) : null;
  const fee = useFee(w, c && minter ? { coin: c, account: minter } : null);
  const runFee = useRunFee(fee, c, minter);
  const [toSlot, setToSlot] = useState(0);
  const [other, setOther] = useState('');
  const [amountText, setAmountText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!c || !minter) {
    return (
      <Dialog open onClose={modal.close} busy={busy} title="Mint">
        {c ? <Banner tone="warning">None of your accounts can mint {c.config.symbol}.</Banner> : <Loading />}
      </Dialog>
    );
  }

  const decimals = c.config.decimals;
  const symbol = c.config.symbol;
  const delayed = minter.delay > 0;
  // Another account, typed or pasted as an address or payment code, wins over the list.
  const otherText = other.trim();
  const otherAccount = isPaymentCode(otherText) ? (parseRequest(otherText)?.account ?? null) : isHex(otherText.toLowerCase(), 32) ? otherText.toLowerCase() : null;
  const own = w.accounts.find((a) => a.slot === toSlot) ?? w.accounts[0];
  const to = otherText ? otherAccount : own ? hex(own.account) : null;
  const toLabel = otherText ? (w.names.get(otherAccount ?? '') ?? `${otherText.slice(0, 10)}…`) : own?.label;
  const parsed = (() => {
    try {
      return amountText.trim() ? parseAmount(amountText.trim(), decimals) : 0n;
    } catch {
      return null;
    }
  })();
  const left = capped(c) ? c.config.maxSupply - c.state.issued : null;
  const ownFee = fee.own ? fee.fee : 0n;
  const problems: string[] = [];
  if (otherText && !otherAccount) problems.push('the account is 64 hex digits or a payment code');
  if (parsed === null) problems.push(`enter an amount with at most ${decimals} decimals`);
  if (parsed && left !== null && parsed > left) problems.push(`the cap leaves ${amountOf(c, left)} to mint`);
  if (to && coin.isReserved(unhex(to))) problems.push('that account is reserved');
  if (fee.problem) problems.push(fee.problem);
  const ready = problems.length === 0 && !!parsed && parsed > 0n && !!to && !!w.user && !!w.info;

  const mint = async () => {
    if (!ready || !w.user || !w.info || !to || !parsed) return;
    setBusy(true);
    setError(null);
    try {
      const signer = await resolveSigner(w.user, minter.slot, w.info.canonical, w.keys, w.identity);
      const action: coin.Action = { effect: { kind: 'mint', to: unhex(to), amount: parsed }, timing: { ...coin.NO_TIMING, tip: runFee } };
      const r = await submitCoinAction(w.node, w.info.status.chainId, c.id, signer, action, undefined, await fee.resolve());
      let queued = false;
      if (delayed) {
        // The node runs it once the lock has passed.
        queued = await queueRun(w.node, c.id, r.account, r.nonce, nowSeconds() + minter.delay)
          .then(() => true)
          .catch(() => false);
      }
      const summary = `${delayed ? 'Schedule mint of' : 'Mint'} ${formatAmount(parsed, decimals)} ${symbol} to ${toLabel}${queued ? ' (run queued)' : ''}`;
      addSubmitted({
        id: r.txId,
        at: nowSeconds(),
        summary: withFee(summary, fee),
        userId: w.user.id,
        ...(ownFee > 0n ? { coin: c.hex, account: hex(minter.account), amount: ownFee.toString() } : {}),
      });
      modal.open(`/tx/${r.txId}`, true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={modal.close} busy={busy} title={`Mint ${symbol}`}>
      <Caption>
        From your {minter.label}
        {delayed ? `: the mint waits ${relativeDuration(minter.delay)} and can be cancelled on that account's page until then.` : ', at once.'}
        {left !== null ? ` The cap leaves ${amountOf(c, left)}.` : ''}
      </Caption>
      <Field label={`Amount (${symbol})`} value={amountText} onChangeText={setAmountText} placeholder="0" autoFocus />
      <span className="field-label">To</span>
      <AccountChoice accounts={w.accounts} slot={otherText ? null : (own?.slot ?? null)} onSlot={(s) => {
          setToSlot(s);
          setOther('');
        }} />
      <Field label="Or another account" value={other} onChangeText={setOther} placeholder="account or payment code" mono />
      <FeeRow fee={fee} run={runFee > 0n ? { amount: runFee, coin: c } : undefined} />
      {problems.length > 0 && (amountText || otherText) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Row>
        <Button title={delayed ? 'Schedule mint' : 'Mint'} onClick={mint} disabled={!ready} loading={busy} />
        <Button title="Cancel" kind="ghost" onClick={modal.close} />
      </Row>
    </Dialog>
  );
}
