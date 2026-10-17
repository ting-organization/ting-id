import React, { useEffect, useRef, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { policyAccount } from '@/chain/accounts';
import { hex, isHex, unhex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { policyFor } from '@/chain/contracts';
import { formatAmount, nowSeconds, parseAmount, shortId } from '@/chain/format';
import * as idp from '@/chain/identity';
import { routeOf } from '@/ui/linkRoutes';
import { LinkError, isLink, netOf, parseLinkOf } from '@/chain/links';
import { queueRun, resolveSigner, submitCoinAction } from '@/state/chain';
import { coinGroups, useAccountViews } from '@/state/coins';
import { useFee, withFee } from '@/state/fees';
import { useContactsHere, useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { AccountPicker, CoinMark, accountNote } from '@/ui/CoinPicker';
import { FeeRow } from '@/ui/FeeRow';
import { usePaymentDraft } from '@/state/paymentDraft';
import { Avatar, Banner, Button, Caption, Card, Field, IconButton, ListItem, Row, Screen, Sheet } from '@/ui/components';
import { lockOf, relativeDuration, sentence } from '@/ui/describe';
import { QrScanner } from '@/ui/Qr';
import { groupTyped, ungroupTyped } from '@/ui/amountInput';
import { SaveContactSheet } from '@/ui/SaveContact';
import { font, radius, spacing, tabular, type, useTheme } from '@/ui/theme';
import { useConfirm } from '@/ui/confirm';
import { Welcome } from '@/ui/Welcome';
import { When, WhenPicker } from '@/ui/WhenPicker';
import { defaultLater, laterProblem } from '@/ui/when';

export default function SendScreen() {
  const t = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ coin?: string; slot?: string; code?: string; to?: string }>();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const contacts = useContactsHere();
  const activeSlot = useStore((s) => s.activeSlot);
  const setActiveSlot = useStore((s) => s.setActiveSlot);
  const activeCoin = useStore((s) => s.activeCoin);
  const setActiveCoin = useStore((s) => s.setActiveCoin);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const setDraft = usePaymentDraft((s) => s.setDraft);
  // The coin and account opened from, else the ones used last, else the first followed coin's Spending.
  const selectedCoin = (params.coin && w.coinById.get(params.coin)) || (activeCoin && w.coinById.get(activeCoin)) || w.coins[0] || null;
  const { views: allViews } = useAccountViews(w.coins, w.accounts);
  const views = (selectedCoin && allViews.get(selectedCoin.hex)) || [];
  const [fromSheet, setFromSheet] = useState(false);
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [when, setWhen] = useState<When>('now');
  const [at, setAt] = useState(() => defaultLater(nowSeconds()));
  // Opened for a recipient, as a contact's Send does: taken once each time.
  const [toParam, setToParam] = useState<string | undefined>(undefined);
  if (params.to !== toParam) {
    setToParam(params.to);
    if (params.to && isHex(params.to, 32)) setTo(params.to.toLowerCase());
  }
  const [picker, setPicker] = useState(false);
  const [naming, setNaming] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [codeNotice, setCodeNotice] = useState<string | null>(null);
  /** The coin a pay link asked for: nothing is sent in another one. */
  const [codeCoin, setCodeCoin] = useState<string | null>(null);
  /** What the pay link said the payment is for. */
  const [note, setNote] = useState<string | null>(null);
  const forgetCode = () => {
    setCodeNotice(null);
    setCodeCoin(null);
    setNote(null);
  };

  const from = views.find((v) => v.account.slot === (params.slot !== undefined ? Number(params.slot) : activeSlot)) ?? views[0];
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

  // A pay link, scanned or pasted, fills the recipient, the amount it asks
  // for and, when it names a followed coin, picks that coin. Returns what is
  // wrong with it, if anything.
  const applyCode = (text: string): string | null => {
    let link;
    try {
      link = parseLinkOf(text, 'pay');
    } catch (e) {
      return sentence((e as Error).message);
    }
    if (w.info && link.net !== netOf(w.info.status.chainId)) return 'This link is for another Ting network.';
    setTo(link.to);
    setCodeNotice(null);
    setCodeCoin(link.coin ?? null);
    setNote(link.note ?? null);
    if (link.amount) setAmount(link.amount);
    if (link.coin && link.coin !== selectedCoin?.hex) {
      if (w.coinById.get(link.coin)) {
        setActiveCoin(link.coin);
        router.setParams({ coin: link.coin, slot: String(from?.account.slot ?? 0) });
      } else {
        setCodeNotice(`The link asks for coin ${shortId(link.coin)}, which you do not follow. Follow it in Settings to send it.`);
      }
    }
    return null;
  };

  const scanned = (text: string): string | null => {
    // A pay link fills this form; any other link opens its own screen.
    const route = routeOf(text, 'inside');
    if (route instanceof LinkError) return sentence(route.message);
    if (route.pathname !== '/send') {
      setScanning(false);
      router.push(route);
      return null;
    }
    const problem = applyCode(text);
    if (!problem) setScanning(false);
    return problem;
  };

  // A pay link scanned elsewhere, or opened from outside the app, arrives as `code`.
  const appliedCode = useRef<string | null>(null);
  const confirm = useConfirm();
  useEffect(() => {
    if (!params.code || appliedCode.current === params.code) return;
    appliedCode.current = params.code;
    const code = params.code;
    // What was typed stays unless the person takes the new code instead.
    const typed = amount.trim() !== '' || to.trim() !== '';
    (typed
      ? confirm({ title: 'Use the new pay link?', body: 'It replaces the recipient and amount you entered.', confirm: 'Use the link', back: 'Keep what I entered' })
      : Promise.resolve(true)
    ).then((yes) => {
      if (!yes) return;
      setAmount('');
      const problem = applyCode(code);
      if (problem) setCodeNotice(problem);
    });
  }, [params.code]); // eslint-disable-line react-hooks/exhaustive-deps

  const parsed = (() => {
    try {
      return amount ? parseAmount(amount, decimals) : 0n;
    } catch {
      return null;
    }
  })();
  // The fee the action carries itself, when its own account pays in this coin.
  const ownFee = fee.own ? fee.fee : 0n;
  const later = when === 'later';
  const lock = from?.account.delay ?? 0;
  const willSchedule = later || delayed;
  const availableNow = from ? coin.available(from.record) : 0n;
  // A scheduled action pays the fee's amount again when it runs, reserved until then.
  const runFee = willSchedule && selectedCoin ? fee.runTip(selectedCoin) : 0n;
  const needed = (parsed ?? 0n) + ownFee + runFee;
  const problems: string[] = [];
  if (!selectedCoin) problems.push('follow a coin in Settings');
  if (!from) problems.push('no account to send from');
  if (to && !isHex(to, 32)) problems.push('the recipient must be a 64-digit hex account');
  if (amount && parsed === null) problems.push(`enter an amount with at most ${decimals} decimals`);
  if (parsed !== null && parsed === 0n && amount) problems.push('amount must be above zero');
  if (fee.problem) problems.push(fee.problem);
  if (from && needed > availableNow) problems.push(`only ${formatAmount(availableNow, decimals)} ${symbol} available`);
  const timeProblem = later ? laterProblem(at, nowSeconds()) : null;
  if (timeProblem) problems.push(timeProblem);
  const coinMismatch = !!codeCoin && codeCoin !== selectedCoin?.hex;
  const ready = problems.length === 0 && to && parsed && parsed > 0n && !coinMismatch;
  // The most that leaves the fee, and a scheduled run's tip, payable.
  const maxAmount = availableNow - ownFee - runFee;
  // What the fee coin leaves on the instant accounts after this payment: with
  // little left, later payments and changes cannot pay their fees.
  const feeCoin = fee.feeCoin;
  const feesLeft = (() => {
    if (!feeCoin || !from) return null;
    const holdings = fee.options.filter((o) => o.coin.hex === feeCoin.hex);
    if (holdings.length === 0 || holdings.some((o) => o.available === null)) return null;
    let left = holdings.reduce((sum, o) => sum + (o.available ?? 0n), 0n);
    if (selectedCoin?.hex === feeCoin.hex && from.account.delay === 0) left -= needed;
    if (!fee.own && fee.payer?.coin.hex === feeCoin.hex) left -= fee.fee;
    return left < 0n ? 0n : left;
  })();
  const feeWarning =
    ready && feeCoin && feesLeft !== null && fee.usual > 0n && feesLeft < fee.usual * 3n
      ? `After this, ${feesLeft === 0n ? 'nothing' : `only ${formatAmount(feesLeft, feeCoin.config.decimals)} ${feeCoin.config.symbol}`} is left for fees. Each later payment or change pays one, about ${formatAmount(fee.usual, feeCoin.config.decimals)} ${feeCoin.config.symbol}, so keep a little ${feeCoin.config.symbol} in Spending.`
      : null;
  const recipientSet = isHex(to, 32);
  const shown = groupTyped(amount || '0');
  const amountSize = shown.length > 9 ? 38 : 52;
  const marks = shown.replace(/\d/g, '').length;
  const amountWidth = Math.min(320, Math.ceil(((shown.length - marks) * 0.58 + marks * 0.3) * amountSize) + 12);
  // The name this device knows the recipient by: a user's account or a contact.
  const toLabel = recipientSet ? (w.names.get(to) ?? null) : null;

  /** Hands the payment to the review page, with the submit that sends it from there. */
  const review = () => {
    if (!ready || !w.user || !w.info || !selectedCoin || !from) return;
    const user = w.user;
    const info = w.info;
    const c = selectedCoin;
    const account = from.account;
    const chosenAt = later ? at : null;
    // It runs at the later of the chosen time and the end of the account's lock.
    const runsAt = (now: number) => Math.max(chosenAt ?? 0, now + lock);
    let sent = false;
    setDraft({
      coin: c,
      amount: parsed!,
      from: { label: `${user.name} · ${account.label}`, account: hex(account.account) },
      to: { account: to },
      runsAt: willSchedule ? runsAt(nowSeconds()) : null,
      chosenAt,
      lock,
      fee: fee.summary,
      feeWarning,
      runFee,
      total: needed,
      available: availableNow,
      submit: async () => {
        if (sent) throw new Error('this payment was sent already');
        if (chosenAt !== null && chosenAt <= nowSeconds() + 60) throw new Error('the chosen time has passed: go back and choose a later one');
        const signer = await resolveSigner(user, account.slot, info.canonical, w.keys, w.identity);
        const action: coin.Action = {
          effect: { kind: 'transfer', to: unhex(to), amount: parsed! },
          timing: { fee: 0n, tip: runFee, notBefore: chosenAt !== null ? BigInt(chosenAt) : 0n, validUntil: 0n, within: null },
        };
        const payer = await fee.resolve();
        const r = await submitCoinAction(w.node, info.status.chainId, c.id, signer, action, undefined, payer);
        sent = true;
        let queued = false;
        if (willSchedule) {
          // The node runs it when due: hand it the run now.
          queued = await queueRun(w.node, c.id, r.account, r.nonce, runsAt(nowSeconds()))
            .then(() => true)
            .catch(() => false);
        }
        const summary = `${willSchedule ? 'Schedule' : 'Send'} ${formatAmount(parsed!, decimals)} ${symbol} to ${toLabel ?? shortId(to)}${queued ? ' (run queued)' : ''}`;
        addSubmitted({
          id: r.txId,
          at: nowSeconds(),
          summary: withFee(summary, fee),
          userId: user.id,
          coin: c.hex,
          account: hex(account.account),
          amount: needed.toString(),
          to,
        });
        // Sent: the form is empty for the next payment.
        setAmount('');
        setTo('');
        setWhen('now');
        setAt(defaultLater(nowSeconds()));
        forgetCode();
        return r.txId;
      },
    });
    router.push('/review');
  };

  if (!w.user) {
    return (
      <Screen title="Send">
        <Welcome />
      </Screen>
    );
  }

  return (
    <Screen title="Send">
      <AccountPicker
        visible={fromSheet}
        onClose={() => setFromSheet(false)}
        groups={coinGroups(w.coins, allViews, true)}
        selected={selectedCoin && from ? { coin: selectedCoin.hex, slot: from.account.slot } : null}
        onSelect={(c, slot) => {
          setActiveCoin(c.hex);
          setActiveSlot(slot);
          router.setParams({ coin: c.hex, slot: String(slot) });
          forgetCode();
        }}
      />
      {w.user && selectedCoin ? (
        <Pressable
          onPress={() => setFromSheet(true)}
          accessibilityRole="button"
          accessibilityLabel="Change the account to send from"
          style={({ pressed }) => ({ opacity: pressed ? 0.85 : 1 })}>
          <Card style={{ paddingVertical: 12 }}>
            <Row gap={12}>
              <CoinMark coin={selectedCoin} size={38} />
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }}>
                  {w.user.name} · {from?.account.label ?? '…'}
                </Text>
                <Caption>
                  {from ? (
                    <>
                      <Text style={tabular}>{formatAmount(availableNow, decimals)}</Text> {symbol} available · {accountNote(from)}
                    </>
                  ) : (
                    'Loading…'
                  )}
                </Caption>
              </View>
              <Ionicons name="chevron-down" size={18} color={t.faint} />
            </Row>
            {from?.account.delay ? (
              <Caption>
                Withdrawals from {from.account.label} wait {relativeDuration(from.account.delay)}; the fee comes from an instant account.
              </Caption>
            ) : null}
          </Card>
        </Pressable>
      ) : null}

      <View style={{ alignItems: 'center', gap: 8, paddingVertical: spacing.md }}>
        <Row gap={8} style={{ alignItems: 'baseline', justifyContent: 'center' }}>
          <TextInput
            value={groupTyped(amount)}
            onChangeText={(v) => setAmount(ungroupTyped(v))}
            placeholder="0"
            placeholderTextColor={t.faint}
            keyboardType="decimal-pad"
            accessibilityLabel={`Amount in ${symbol}`}
            // As wide as its figures, so the symbol sits beside them; a long amount in smaller figures.
            style={[{ color: t.text, fontSize: amountSize, letterSpacing: -2, ...font.extrabold, textAlign: 'center', width: amountWidth, padding: 0, outlineWidth: 0 }, tabular]}
          />
          <Text style={{ color: t.faint, fontSize: 20, ...font.bold }}>{symbol}</Text>
        </Row>
        {from && maxAmount > 0n ? (
          <Pressable
            onPress={() => setAmount(formatAmount(maxAmount, decimals).replace(/\s/g, ''))}
            accessibilityRole="button"
            style={({ pressed }) => ({
              borderWidth: 1,
              borderColor: t.border,
              backgroundColor: t.card,
              borderRadius: radius.pill,
              paddingHorizontal: 12,
              paddingVertical: 4,
              opacity: pressed ? 0.7 : 1,
            })}>
            <Text style={{ color: t.muted, fontSize: 12.5, ...font.bold }}>Max</Text>
          </Pressable>
        ) : null}
      </View>

      <Card style={{ paddingVertical: 12 }}>
        {recipientSet ? (
          <Row gap={12}>
            <Avatar name={toLabel ?? '#'} color={t.primary} size={40} soft />
            <View style={{ flex: 1, gap: 1 }}>
              <Caption>To</Caption>
              <Text style={{ color: t.text, fontSize: 15, ...font.bold }} numberOfLines={1}>
                {toLabel ?? 'New recipient'}
              </Text>
              <Text style={{ color: t.faint, fontSize: 12, fontFamily: type.mono.fontFamily }}>{to}</Text>
              {note ? <Caption numberOfLines={2}>For: {note}</Caption> : null}
              {!toLabel ? <Button title="Save as contact" kind="ghost" small icon="person-add-outline" onPress={() => setNaming(true)} /> : null}
            </View>
            <Button
              title="Change"
              kind="ghost"
              small
              onPress={() => {
                setTo('');
                forgetCode();
              }}
            />
          </Row>
        ) : (
          <>
            <Caption>To</Caption>
            <Row gap={8}>
              <View style={{ flex: 1 }}>
                <Field
                  value={to}
                  onChangeText={(v) => {
                    if (isLink(v)) {
                      const problem = applyCode(v);
                      if (problem) setCodeNotice(problem);
                      return;
                    }
                    setTo(v.trim().toLowerCase());
                    forgetCode();
                  }}
                  placeholder="Account or pay link"
                  autoCapitalize="none"
                  autoCorrect={false}
                  accessibilityLabel="Recipient"
                />
              </View>
              <IconButton icon="people-outline" label="Recipients" onPress={() => setPicker(true)} />
              <IconButton icon="scan-outline" label="Scan a code" onPress={() => setScanning(true)} />
            </Row>
          </>
        )}
      </Card>

      <WhenPicker when={when} at={at} lock={lock} onWhen={setWhen} onAt={setAt} />

      {w.user ? (
        <View style={{ paddingHorizontal: 4 }}>
          <FeeRow fee={fee} run={selectedCoin && runFee > 0n ? { amount: runFee, coin: selectedCoin } : undefined} />
        </View>
      ) : null}

      {codeNotice ? (
            <Banner tone="warning">{codeNotice}</Banner>
          ) : coinMismatch ? (
            <Banner tone="warning">The pay link asks for {w.coinById.get(codeCoin!)?.config.symbol ?? shortId(codeCoin!)}; choose that coin to send.</Banner>
          ) : null}
      {problems.length > 0 && (amount || to) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {feeWarning ? <Banner tone="warning">{feeWarning}</Banner> : null}
      <Button title="Review" large onPress={review} disabled={!ready} />

      <QrScanner visible={scanning} onClose={() => setScanning(false)} onScan={scanned} />
      <Sheet visible={picker} onClose={() => setPicker(false)} title="Recipient">
        {recipients.length === 0 ? <Caption>No contacts yet.</Caption> : null}
        {recipients.map((r) => (
          <ListItem
            key={r.account + r.label}
            left={<Avatar name={r.label} color={r.color} size={36} />}
            title={r.label}
            subtitle={r.sub}
            onPress={() => {
              setTo(r.account);
              forgetCode();
              setPicker(false);
            }}
          />
        ))}
      </Sheet>
      {recipientSet ? <SaveContactSheet account={to} visible={naming} onClose={() => setNaming(false)} /> : null}
    </Screen>
  );
}
