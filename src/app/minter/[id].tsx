// Handing a coin's minting right to another account, or giving it up for
// good, with the coin tools switched on. Moving it to Savings makes every
// later mint wait out the lock. From a time-locked account the change itself
// waits too, and can be cancelled.
import React, { useState } from 'react';
import { Redirect, Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { hex, unhex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { nowSeconds } from '@/chain/format';
import { accountIn } from '@/chain/links';
import { queueRun, resolveSigner, submitCoinAction, submission, txParams } from '@/state/chain';
import { useCoinInfos } from '@/state/coins';
import { useFee, useRunFee, withFee } from '@/state/fees';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { AccountChoice } from '@/ui/AccountChoice';
import { minterAccount } from '@/ui/coinFacts';
import { Banner, Button, Caption, Field, Loading, Screen, Segmented } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { relativeDuration, sentence } from '@/ui/describe';
import { useOwnerConfirm } from '@/ui/ownerConfirm';
import { FeeRow } from '@/ui/FeeRow';
import { useHoldWhileBusy } from '@/ui/holdWhileBusy';

type Target = 'mine' | 'other' | 'none';

export default function MinterScreen() {
  const coinTools = useStore((s) => !!s.settings.coinTools);
  if (!coinTools) return <Redirect href="/" />;
  return <Minter />;
}

function Minter() {
  const router = useRouter();
  const { id = '' } = useLocalSearchParams<{ id: string }>();
  const w = useWorkspace();
  const ownerConfirm = useOwnerConfirm();
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
  useHoldWhileBusy(busy);
  const confirm = useConfirm();
  const [error, setError] = useState<string | null>(null);

  if (!c || !minter) {
    return <Screen belowHeader>{c ? <Banner tone="warning">None of your accounts can mint {c.config.symbol}.</Banner> : <Loading />}</Screen>;
  }

  const symbol = c.config.symbol;
  const delayed = minter.delay > 0;
  const others = w.accounts.filter((a) => a.slot !== minter.slot);
  // Savings by default: the safer home for minting.
  const chosen = others.find((a) => a.slot === slot) ?? others.find((a) => a.delay > 0) ?? others[0] ?? null;
  const otherText = other.trim();
  const otherAccount = accountIn(otherText);
  const next = target === 'none' ? hex(coin.ZERO_ACCOUNT) : target === 'other' ? otherAccount : chosen ? hex(chosen.account) : null;
  const label = target === 'none' ? 'no one' : target === 'other' ? (w.names.get(otherAccount ?? '') ?? otherAccount ?? '') : chosen?.label;
  const problems: string[] = [];
  if (target === 'other' && otherText && !otherAccount) problems.push('the account is 64 hex digits or a pay link');
  if (next && next === hex(coin.STATE_KEY)) problems.push('that account is reserved');
  if (fee.problem) problems.push(fee.problem);
  const ready = problems.length === 0 && !!next && !!w.user && !!w.info;

  const submit = async () => {
    if (!ready || !w.user || !w.info || !next || busy) return;
    // Giving minting up, or handing it to someone else, cannot be taken back from here.
    if (
      target === 'none' &&
      !(await confirm({
        title: `Give up minting ${symbol}?`,
        body: `No one will ever be able to mint ${symbol} again, you included.`,
        confirm: 'Give up minting',
        back: 'Keep minting',
        danger: true,
      }))
    )
      return;
    if (target === 'other' && !(await confirm({ title: `Hand minting of ${symbol} to ${label}?`, body: `Only the account ${next} can mint it afterwards, or hand it on.`, confirm: 'Hand it over', back: 'Back' })))
      return;
    setBusy(true);
    setError(null);
    try {
      if (!(await ownerConfirm(target === 'none' ? `Give up minting ${symbol}` : `Move minting of ${symbol}`, target === 'none' ? undefined : `to ${label}`))) return;
      const signer = await resolveSigner(w.user, minter.slot, w.info.canonical, w.keys, w.identity);
      const action: coin.Action = { effect: { kind: 'setMinter', minter: unhex(next) }, timing: { ...coin.NO_TIMING, tip: runFee } };
      const payer = await fee.resolve();
      const out = await submission(() => submitCoinAction(w.node, w.info!.status.chainId, c.id, signer, action, undefined, payer));
      const r = out.unsure ? { txId: out.txId, account: out.action!.account, nonce: out.action!.nonce } : out.result;
      const queued = delayed
        ? await queueRun(w.node, c.id, r.account, r.nonce, nowSeconds() + minter.delay)
            .then(() => true)
            .catch(() => false)
        : true;
      const summary =
        (target === 'none' ? `Give up minting ${symbol}` : `${delayed ? 'Schedule moving' : 'Move'} minting of ${symbol} to ${label}`) + (queued ? '' : ' · run it from the wallet when due');
      addSubmitted({ id: r.txId, at: nowSeconds(), summary: withFee(summary, fee), userId: w.user.id });
      router.replace({ pathname: '/tx/[id]', params: txParams({ txId: r.txId, unsure: out.unsure }) });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen belowHeader>
      <Stack.Screen options={{ title: `Minting of ${symbol}` }} />
      <Caption>
        Your {minter.label} mints {symbol} now.
        {delayed ? ` A change from it waits ${relativeDuration(minter.delay)} and can be cancelled until then.` : ''}
      </Caption>
      <Segmented
        options={[
          { value: 'mine', label: 'Your account' },
          { value: 'other', label: 'Someone else' },
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
      {target === 'other' ? (
        <>
          <Field label="Account" value={other} onChangeText={setOther} placeholder="account or pay link" autoCapitalize="none" autoCorrect={false} autoFocus />
          {otherAccount ? <Caption>{otherAccount}</Caption> : null}
        </>
      ) : null}
      <FeeRow fee={fee} boxed run={runFee > 0n ? { amount: runFee, coin: c } : undefined} />
      {problems.length > 0 ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Button
        title={target === 'none' ? 'Give up minting' : delayed ? 'Schedule the move' : 'Move minting'}
        kind={target === 'none' ? 'danger' : 'primary'}
        large
        onPress={submit}
        disabled={!ready}
        loading={busy}
      />
    </Screen>
  );
}
