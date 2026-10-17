// Setting up someone's identity from their create link (LINKS.md): the
// identity they built on their phone, created here with this user paying the
// fee, and coins sent to its Spending to start with. Anyone may submit a
// creation, but only exactly as built, so the link cannot be turned into
// another identity; the name is only what the link says.
import React, { useEffect, useState } from 'react';
import { Switch, Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount, nowSeconds, parseAmount } from '@/chain/format';
import * as idp from '@/chain/identity';
import { Link, netOf, parseLinkOf } from '@/chain/links';
import { fetchIdentity, resolveSigner, submitCoinAction, submitWithFee } from '@/state/chain';
import { useAccountViews } from '@/state/coins';
import { feeCoinOf, useFee, withFee } from '@/state/fees';
import { uuid } from '@/state/keystore';
import { checkOwner } from '@/state/ownerCheck';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { CoinMark } from '@/ui/CoinPicker';
import { Avatar, Banner, Button, Caption, Card, Detail, DetailCard, DetailValue, Field, IdChip, Row, Screen, Sub } from '@/ui/components';
import { relativeDuration, sentence } from '@/ui/describe';
import { FeeRow } from '@/ui/FeeRow';
import { font, spacing, type, useTheme } from '@/ui/theme';

export default function SponsorScreen() {
  const t = useTheme();
  const router = useRouter();
  const { code } = useLocalSearchParams<{ code?: string }>();
  const w = useWorkspace();
  const chosenFeeCoin = useStore((s) => s.settings.feeCoin);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const addContact = useStore((s) => s.addContact);
  const ownerCheck = useStore((s) => s.settings.confirmPayments !== false);

  // What the link holds: the creation, the name it gives, and the coins it asks for.
  type Parsed = { link: Extract<Link, { request: 'create' }>; settings: idp.Settings; id: Uint8Array } | { problem: string };
  const parsed: Parsed = (() => {
    try {
      const link = parseLinkOf(code ?? '', 'create');
      if (w.info && link.net !== netOf(w.info.status.chainId)) return { problem: 'this link is for another Ting network' };
      const settings = idp.decodeCreation(link.input);
      if (!settings) return { problem: 'the link does not hold an identity this app can read' };
      const id = idp.idOf(link.input);
      const invalid = idp.validSettings(settings, id);
      if (invalid) return { problem: `the registry would refuse this identity: ${invalid}` };
      return { link, settings, id };
    } catch (e) {
      return { problem: (e as Error).message };
    }
  })();
  const ok = 'link' in parsed ? parsed : null;
  const name = ok?.link.name?.trim() || null;

  // The coin to start it with: the one the link asks for when followed, else the fee coin.
  const c = (ok?.link.coin && w.coinById.get(ok.link.coin)) || feeCoinOf(chosenFeeCoin, w.coins, w.info ? hex(w.info.status.chainId) : null);
  const askedOther = !!ok?.link.coin && !w.coinById.get(ok.link.coin);
  const from = w.accounts.find((a) => a.delay === 0) ?? null;
  const { views } = useAccountViews(c ? [c] : [], from ? [from] : []);
  const view = c ? views.get(c.hex)?.[0] : undefined;
  const available = view ? coin.available(view.record) : null;

  const [amount, setAmount] = useState(ok?.link.amount ?? '');
  const [saveContact, setSaveContact] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const creationFee = useFee(w, null);
  const paymentFee = useFee(w, c && from ? { coin: c, account: from } : null);

  // Whether a block has the identity already: then only the coins are sent.
  const [exists, setExists] = useState<boolean | null>(null);
  const height = w.info?.status.height;
  const registry = w.info?.canonical.registry ?? null;
  const idHex = ok ? hex(ok.id) : null;
  useEffect(() => {
    let alive = true;
    if (!registry || !idHex) return;
    fetchIdentity(w.node, registry, ok!.id)
      .then((info) => alive && setExists(info !== null))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [w.node, registry, idHex, height]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!ok) {
    return (
      <Screen belowHeader>
        <Banner tone="danger">{sentence('problem' in parsed ? parsed.problem : 'this is not a create link')}</Banner>
        <Button title="Back" kind="secondary" onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
      </Screen>
    );
  }

  const spending = registry ? idp.account(registry, ok.id, 0) : null;
  const decimals = c?.config.decimals ?? 0;
  const symbol = c?.config.symbol ?? '';
  const value = (() => {
    try {
      return amount.trim() ? parseAmount(amount, decimals) : 0n;
    } catch {
      return null;
    }
  })();
  const ownFee = paymentFee.own ? paymentFee.fee : 0n;
  const creating = exists === false;
  const problems: string[] = [];
  if (!w.user || !from) problems.push('add a user with an account first');
  if (value === null) problems.push(`enter an amount with at most ${decimals} decimals`);
  if (value && available !== null && value + ownFee > available) problems.push(`only ${formatAmount(available, decimals)} ${symbol} available`);
  if (creating && creationFee.problem) problems.push(creationFee.problem);
  if (value && paymentFee.problem) problems.push(paymentFee.problem);
  if (exists && !value) problems.push('it is on chain already: enter coins to send it');
  const ready = problems.length === 0 && exists !== null && !!w.info && !!spending;
  const title = creating ? (value ? `Create and send ${formatAmount(value, decimals)} ${symbol}` : 'Create the identity') : `Send ${formatAmount(value ?? 0n, decimals)} ${symbol}`;
  const who = name ?? 'the identity';

  const submit = async () => {
    if (!ready || !w.user || !w.info || !from || !spending || !c) return;
    setBusy(true);
    setError(null);
    try {
      if (ownerCheck) {
        const r = await checkOwner(creating ? `Set up ${who}` : `Send to ${who}`, value ? `${formatAmount(value, decimals)} ${symbol}` : undefined);
        if (r === 'cancelled') return setBusy(false);
        if (r === 'failed') throw new Error('the phone did not confirm it is you');
      }
      const chain = w.info.status.chainId;
      let shown: string | null = null;
      if (creating) {
        const txId = await submitWithFee(w.node, chain, w.info.canonical, { target: w.info.canonical.registry, input: idp.createInput(ok.link.input) }, await creationFee.resolve());
        addSubmitted({ id: txId, at: nowSeconds(), summary: withFee(`Create ${who}`, creationFee), userId: w.user.id });
        shown = txId;
      }
      if (value) {
        const signer = await resolveSigner(w.user, from.slot, w.info.canonical, w.keys, w.identity);
        const action: coin.Action = { effect: { kind: 'transfer', to: spending, amount: value }, timing: { fee: 0n, tip: 0n, notBefore: 0n, validUntil: 0n, within: null } };
        const r = await submitCoinAction(w.node, chain, c.id, signer, action, undefined, await paymentFee.resolve());
        addSubmitted({
          id: r.txId,
          at: nowSeconds(),
          summary: withFee(`Send ${formatAmount(value, decimals)} ${symbol} to ${who}`, paymentFee),
          userId: w.user.id,
          coin: c.hex,
          account: hex(from.account),
          amount: (value + ownFee).toString(),
          to: hex(spending),
        });
        shown = r.txId;
      }
      if (saveContact && name && !w.names.has(hex(spending))) addContact({ id: uuid(), name, account: hex(spending) });
      router.replace({ pathname: '/tx/[id]', params: { id: shown!, sent: '1' } });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  const slots = ok.settings.slots;
  return (
    <Screen belowHeader>
      <Card>
        <Row gap={14}>
          <Avatar name={name ?? '?'} color={t.primary} size={48} soft />
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={[type.heading, { color: t.text }]} numberOfLines={1}>
              {name ? `Set up ${name}` : 'Set up an identity'}
            </Text>
            <Caption>{name ? 'That is the name the link gives: check it with whoever sent it.' : 'The link gives no name.'}</Caption>
          </View>
        </Row>
        <IdChip label="ID" value={hex(ok.id)} />
      </Card>

      {exists === true ? <Banner tone="info">This identity is on chain already: only the coins are sent.</Banner> : null}
      {exists === false ? <Banner tone="info">It is not on chain yet: this creates it, exactly as built on their phone.</Banner> : null}

      <DetailCard>
        <Detail label="Keys" first>
          <DetailValue>{ok.settings.keys.length}</DetailValue>
        </Detail>
        <Detail label="Guardians">
          <DetailValue>{ok.settings.guardians.length === 0 ? 'None yet' : ok.settings.guardians.length}</DetailValue>
        </Detail>
        {slots.map((delay, slot) => (
          <Detail key={slot} label={slot === 0 ? 'Spending' : slot === 1 ? 'Savings' : `Account ${slot}`}>
            <DetailValue>{delay ? `withdrawals wait ${relativeDuration(delay)}` : 'instant'}</DetailValue>
          </Detail>
        ))}
        <Detail label="Changes wait">
          <DetailValue>{relativeDuration(ok.settings.changeDelay)}</DetailValue>
        </Detail>
      </DetailCard>

      {c ? (
        <Card>
          <Row gap={12}>
            <CoinMark coin={c} size={36} />
            <View style={{ flex: 1 }}>
              <Sub>Coins to start with</Sub>
              <Caption>
                To its Spending, from your {from?.label ?? 'account'}
                {available !== null ? ` · ${formatAmount(available, decimals)} ${symbol} available` : ''}
              </Caption>
            </View>
          </Row>
          <Field label={`Amount (${symbol})`} value={amount} onChangeText={setAmount} placeholder="0" keyboardType="decimal-pad" hint={ok.link.amount ? `The link asks for ${ok.link.amount} ${symbol}.` : 'Optional: 0 creates it without coins.'} />
          {askedOther ? <Caption style={{ color: t.warning }}>The link asks for a coin you do not follow; this sends {symbol} instead.</Caption> : null}
        </Card>
      ) : null}

      <View style={{ paddingHorizontal: 4, gap: spacing.sm }}>
        {creating ? <FeeRow fee={creationFee} label="Creation fee" /> : null}
        {value ? <FeeRow fee={paymentFee} label="Payment fee" /> : null}
      </View>

      {name && spending && !w.names.has(hex(spending)) ? (
        <Row style={{ justifyContent: 'space-between', paddingHorizontal: 4 }}>
          <Text style={{ color: t.text, fontSize: 14.5, ...font.semibold, flex: 1 }}>Save {name} as a contact</Text>
          <Switch accessibilityLabel={`Save ${name} as a contact`} value={saveContact} onValueChange={setSaveContact} trackColor={{ true: t.primary, false: t.border }} />
        </Row>
      ) : null}

      {problems.length > 0 && exists !== null ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Button title={exists === null ? 'Looking it up…' : title} large loading={busy} disabled={!ready} onPress={submit} />
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
