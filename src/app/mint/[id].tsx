// Minting, with the coin tools switched on, from the account that holds the
// coin's minting right. From a time-locked account the mint is scheduled: it
// waits out the lock, can be cancelled on the account's page until then, and
// the node runs it when due.
import React, { useState } from 'react';
import { Text } from 'react-native';
import { Redirect, Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { hex, unhex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount, nowSeconds, parseAmount } from '@/chain/format';
import { accountIn } from '@/chain/links';
import { queueRun, resolveSigner, submitCoinAction, submission, txParams } from '@/state/chain';
import { useCoinInfos } from '@/state/coins';
import { useFee, useRunFee, withFee } from '@/state/fees';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { AccountChoice } from '@/ui/AccountChoice';
import { amountOf, capped, minterAccount } from '@/ui/coinFacts';
import { Banner, Button, Caption, Field, Loading, Screen } from '@/ui/components';
import { relativeDuration, sentence } from '@/ui/describe';
import { useOwnerConfirm } from '@/ui/ownerConfirm';
import { FeeRow } from '@/ui/FeeRow';
import { font, useTheme } from '@/ui/theme';
import { useHoldWhileBusy } from '@/ui/holdWhileBusy';

export default function MintScreen() {
  const coinTools = useStore((s) => !!s.settings.coinTools);
  if (!coinTools) return <Redirect href="/" />;
  return <Mint />;
}

function Mint() {
  const t = useTheme();
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
  const [toSlot, setToSlot] = useState(0);
  const [other, setOther] = useState('');
  const [amountText, setAmountText] = useState('');
  const [busy, setBusy] = useState(false);
  useHoldWhileBusy(busy);
  const [error, setError] = useState<string | null>(null);

  if (!c || !minter) {
    return <Screen belowHeader>{c ? <Banner tone="warning">None of your accounts can mint {c.config.symbol}.</Banner> : <Loading />}</Screen>;
  }

  const decimals = c.config.decimals;
  const symbol = c.config.symbol;
  const delayed = minter.delay > 0;
  // Another account, typed or pasted as an account or pay link, wins over the list.
  const otherText = other.trim();
  const otherAccount = accountIn(otherText);
  const own = w.accounts.find((a) => a.slot === toSlot) ?? w.accounts[0];
  const to = otherText ? otherAccount : own ? hex(own.account) : null;
  const toLabel = otherText ? (w.names.get(otherAccount ?? '') ?? otherAccount ?? '') : own?.label;
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
  if (otherText && !otherAccount) problems.push('the account is 64 hex digits or a pay link');
  if (parsed === null) problems.push(`enter an amount with at most ${decimals} decimals`);
  if (parsed && left !== null && parsed > left) problems.push(`the cap leaves ${amountOf(c, left)} to mint`);
  if (to && coin.isReserved(unhex(to))) problems.push('that account is reserved');
  if (fee.problem) problems.push(fee.problem);
  const ready = problems.length === 0 && !!parsed && parsed > 0n && !!to && !!w.user && !!w.info;

  const mint = async () => {
    if (!ready || !w.user || !w.info || !to || !parsed || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (!(await ownerConfirm(`Mint ${formatAmount(parsed, decimals)} ${symbol}`, `to ${toLabel}`))) return;
      const signer = await resolveSigner(w.user, minter.slot, w.info.canonical, w.keys, w.identity);
      const action: coin.Action = { effect: { kind: 'mint', to: unhex(to), amount: parsed }, timing: { ...coin.NO_TIMING, tip: runFee } };
      const payer = await fee.resolve();
      const out = await submission(() => submitCoinAction(w.node, w.info!.status.chainId, c.id, signer, action, undefined, payer));
      const r = out.unsure ? { txId: out.txId, account: out.action!.account, nonce: out.action!.nonce } : out.result;
      let queued = false;
      if (delayed) {
        // The node runs it once the lock has passed.
        queued = await queueRun(w.node, c.id, r.account, r.nonce, nowSeconds() + minter.delay)
          .then(() => true)
          .catch(() => false);
      }
      const summary = `${delayed ? 'Schedule mint of' : 'Mint'} ${formatAmount(parsed, decimals)} ${symbol} to ${toLabel}${delayed && !queued ? ' · run it from the wallet when due' : ''}`;
      addSubmitted({
        id: r.txId,
        at: nowSeconds(),
        summary: withFee(summary, fee),
        userId: w.user.id,
        ...(ownFee > 0n ? { coin: c.hex, account: hex(minter.account), amount: ownFee.toString() } : {}),
      });
      router.replace({ pathname: '/tx/[id]', params: txParams({ txId: r.txId, unsure: out.unsure }) });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen belowHeader>
      <Stack.Screen options={{ title: `Mint ${symbol}` }} />
      <Caption>
        From your {minter.label}
        {delayed ? `: the mint waits ${relativeDuration(minter.delay)} and can be cancelled on that account's page until then.` : ', at once.'}
        {left !== null ? ` The cap leaves ${amountOf(c, left)}.` : ''}
      </Caption>
      <Field label={`Amount (${symbol})`} value={amountText} onChangeText={setAmountText} placeholder="0" keyboardType="decimal-pad" autoFocus />
      <Text style={{ color: t.muted, fontSize: 12.5, ...font.semibold }}>To</Text>
      <AccountChoice
        accounts={w.accounts}
        slot={otherText ? null : (own?.slot ?? null)}
        onSlot={(s) => {
          setToSlot(s);
          setOther('');
        }}
      />
      <Field label="Or another account" value={other} onChangeText={setOther} placeholder="account or pay link" autoCapitalize="none" autoCorrect={false} />
      {otherAccount ? <Caption>{otherAccount}</Caption> : null}
      <FeeRow fee={fee} boxed run={runFee > 0n ? { amount: runFee, coin: c } : undefined} />
      {problems.length > 0 && (amountText || otherText) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Button title={delayed ? 'Schedule mint' : 'Mint'} large onPress={mint} disabled={!ready} loading={busy} />
    </Screen>
  );
}
