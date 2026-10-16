// Handing a coin's minting right to another account, or giving it up for
// good. Moving it to Savings makes every later mint wait out the lock. From a
// time-locked account the change itself waits too, and can be cancelled.
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { hex, isHex, unhex } from '@/core/chain/bytes';
import * as coin from '@/core/chain/coin';
import { nowSeconds } from '@/core/chain/format';
import { isPaymentCode, parseRequest } from '@/core/chain/request';
import { queueRun, resolveSigner, submitCoinAction } from '@/core/state/chain';
import { useCoinInfos } from '@/core/state/coins';
import { useFee, useRunFee, withFee } from '@/core/state/fees';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { relativeDuration, sentence } from '@/core/describe';
import { AccountChoice } from '@/ui/AccountChoice';
import { minterAccount } from '@/core/coinFacts';
import { FeeRow } from '@/ui/FeeRow';
import { Banner, Button, Caption, Dialog, Field, Loading, Row, Segmented } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { useModal } from '@/ui/modal';

type Target = 'mine' | 'other' | 'none';

export function MinterDialog() {
  const modal = useModal();
  const { id = '' } = useParams<{ id: string }>();
  const w = useWorkspace();
  const addSubmitted = useStore((s) => s.addSubmitted);
  const { infos } = useCoinInfos([id]);
  const c = infos.get(id) ?? null;
  const minter = c ? minterAccount(c, w.accounts) : null;
  const fee = useFee(w, c && minter ? { coin: c, account: minter } : null);
  const runFee = useRunFee(fee, c, minter);
  const [target, setTarget] = useState<Target>('mine');
  const [slot, setSlot] = useState<number | null>(null);
  const [other, setOther] = useState('');
  const [busy, setBusy] = useState(false);
  const confirm = useConfirm();
  const [error, setError] = useState<string | null>(null);

  if (!c || !minter) {
    return (
      <Dialog open onClose={modal.close} busy={busy} title="Move minting">
        {c ? <Banner tone="warning">None of your accounts can mint {c.config.symbol}.</Banner> : <Loading />}
      </Dialog>
    );
  }

  const symbol = c.config.symbol;
  const delayed = minter.delay > 0;
  const others = w.accounts.filter((a) => a.slot !== minter.slot);
  // Savings by default: the safer home for minting.
  const chosen = others.find((a) => a.slot === slot) ?? others.find((a) => a.delay > 0) ?? others[0] ?? null;
  const otherText = other.trim();
  const otherAccount = isPaymentCode(otherText) ? (parseRequest(otherText)?.account ?? null) : isHex(otherText.toLowerCase(), 32) ? otherText.toLowerCase() : null;
  const next = target === 'none' ? hex(coin.ZERO_ACCOUNT) : target === 'other' ? otherAccount : chosen ? hex(chosen.account) : null;
  const label = target === 'none' ? 'no one' : target === 'other' ? (w.names.get(otherAccount ?? '') ?? `${otherText.slice(0, 10)}…`) : chosen?.label;
  const problems: string[] = [];
  if (target === 'other' && otherText && !otherAccount) problems.push('the account is 64 hex digits or a payment code');
  if (next && next === hex(coin.STATE_KEY)) problems.push('that account is reserved');
  if (fee.problem) problems.push(fee.problem);
  const ready = problems.length === 0 && !!next && !!w.user && !!w.info;

  const submit = async () => {
    if (!ready || !w.user || !w.info || !next) return;
    // Giving minting up, or handing it to someone else, cannot be taken back from here.
    if (target === 'none' && !(await confirm({ title: `Give up minting ${symbol}?`, body: `No one will ever be able to mint ${symbol} again, you included.`, confirm: 'Give up minting', back: 'Keep minting', danger: true })))
      return;
    if (target === 'other' && !(await confirm({ title: `Hand minting of ${symbol} to ${label}?`, body: `Only the account ${next} can mint it afterwards, or hand it on.`, confirm: 'Hand it over', back: 'Back' }))) return;
    setBusy(true);
    setError(null);
    try {
      const signer = await resolveSigner(w.user, minter.slot, w.info.canonical, w.keys, w.identity);
      const action: coin.Action = { effect: { kind: 'setMinter', minter: unhex(next) }, timing: { ...coin.NO_TIMING, tip: runFee } };
      const r = await submitCoinAction(w.node, w.info.status.chainId, c.id, signer, action, undefined, await fee.resolve());
      if (delayed) await queueRun(w.node, c.id, r.account, r.nonce, nowSeconds() + minter.delay).catch(() => {});
      const summary = target === 'none' ? `Give up minting ${symbol}` : `${delayed ? 'Schedule moving' : 'Move'} minting of ${symbol} to ${label}`;
      addSubmitted({ id: r.txId, at: nowSeconds(), summary: withFee(summary, fee), userId: w.user.id });
      modal.open(`/tx/${r.txId}`, true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={modal.close} busy={busy} title={`Minting of ${symbol}`}>
      <Caption>
        Your {minter.label} mints {symbol} now.
        {delayed ? ` A change from it waits ${relativeDuration(minter.delay)} and can be cancelled until then.` : ''}
      </Caption>
      <Segmented
        options={[
          { value: 'mine', label: 'To your account' },
          { value: 'other', label: 'To someone else' },
          { value: 'none', label: 'Give it up' },
        ]}
        value={target}
        onChange={setTarget}
      />
      {target === 'mine' ? (
        others.length > 0 ? (
          <AccountChoice
            accounts={others}
            slot={chosen?.slot ?? null}
            onSlot={setSlot}
            note={(a) => (a.delay > 0 ? `every mint waits ${relativeDuration(a.delay)} and can be cancelled` : 'mints at once')}
          />
        ) : (
          <Caption>You have no other account.</Caption>
        )
      ) : null}
      {target === 'other' ? <Field label="Account" value={other} onChangeText={setOther} placeholder="account or payment code" mono autoFocus /> : null}
      <FeeRow fee={fee} run={runFee > 0n ? { amount: runFee, coin: c } : undefined} />
      {problems.length > 0 ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Row>
        <Button
          title={target === 'none' ? 'Give up minting' : delayed ? 'Schedule the move' : 'Move minting'}
          kind={target === 'none' ? 'danger' : 'primary'}
          onClick={submit}
          disabled={!ready}
          loading={busy}
        />
        <Button title="Cancel" kind="ghost" onClick={modal.close} />
      </Row>
    </Dialog>
  );
}
