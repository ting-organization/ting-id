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
import { isSignCode } from '@/chain/offchain';
import { isPaymentCode, parseRequest } from '@/chain/request';
import { queueRun, resolveSigner, submitCoinAction } from '@/state/chain';
import { coinGroups, useAccountViews } from '@/state/coins';
import { useFee, withFee } from '@/state/fees';
import { runTip } from '@/state/payer';
import { useContactsHere, useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { AccountPicker, CoinMark, accountNote } from '@/ui/CoinPicker';
import { FeeRow } from '@/ui/FeeRow';
import { Avatar, Banner, Button, Caption, Card, Field, IconButton, ListItem, Row, Screen, Segmented, Sheet } from '@/ui/components';
import { lockOf, relativeDuration, runsIn, sentence } from '@/ui/describe';
import { QrScanner } from '@/ui/Qr';
import { font, radius, spacing, tabular, type, useTheme } from '@/ui/theme';
import { useConfirm } from '@/ui/confirm';

const SCHEDULE = [
  { value: 'now', label: 'Now', seconds: 0 },
  { value: '1h', label: '+1 hour', seconds: 3600 },
  { value: '1d', label: '+1 day', seconds: 86_400 },
  { value: '7d', label: '+7 days', seconds: 604_800 },
] as const;

type When = (typeof SCHEDULE)[number]['value'];

function whenOf(value: string | undefined): When | null {
  return SCHEDULE.find((s) => s.value === value)?.value ?? null;
}

export default function SendScreen() {
  const t = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ coin?: string; slot?: string; code?: string; when?: string }>();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const contacts = useContactsHere();
  const activeSlot = useStore((s) => s.activeSlot);
  const setActiveSlot = useStore((s) => s.setActiveSlot);
  const activeCoin = useStore((s) => s.activeCoin);
  const setActiveCoin = useStore((s) => s.setActiveCoin);
  const addSubmitted = useStore((s) => s.addSubmitted);
  // The coin and account opened from, else the ones used last, else the first followed coin's Spending.
  const selectedCoin = (params.coin && w.coinById.get(params.coin)) || (activeCoin && w.coinById.get(activeCoin)) || w.coins[0] || null;
  const { views: allViews } = useAccountViews(w.coins, w.accounts);
  const views = (selectedCoin && allViews.get(selectedCoin.hex)) || [];
  const [fromSheet, setFromSheet] = useState(false);
  const [to, setTo] = useState('');
  const [toLabel, setToLabel] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const [schedule, setSchedule] = useState<When>(whenOf(params.when) ?? 'now');
  // Opened again with a time, as the wallet's Schedule does: the new time is taken once.
  const [whenParam, setWhenParam] = useState(params.when);
  if (params.when !== whenParam) {
    setWhenParam(params.when);
    const when = whenOf(params.when);
    if (when) setSchedule(when);
  }
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
        router.setParams({ coin: request.coin, slot: String(from?.account.slot ?? 0) });
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
      router.push({ pathname: '/sign', params: { code: text } });
      return null;
    }
    const problem = applyCode(text);
    if (!problem) setScanning(false);
    return problem;
  };

  // A payment code scanned elsewhere, or opened as a link, arrives as `code`.
  const appliedCode = useRef<string | null>(null);
  const confirm = useConfirm();
  useEffect(() => {
    if (!params.code || appliedCode.current === params.code) return;
    appliedCode.current = params.code;
    const code = params.code;
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
  // The most that leaves the fee, and a scheduled run's tip, payable.
  const maxAmount = availableNow - ownFee - runFee;
  const recipientSet = isHex(to, 32);

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
      router.push({ pathname: '/tx/[id]', params: { id: r.txId } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen title="Send">
      {!w.user ? <Banner>Create a user first.</Banner> : null}
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
                Sending from {from.account.label} is scheduled {relativeDuration(from.account.delay)} ahead and can be cancelled until then; its fee comes from an
                instant account.
              </Caption>
            ) : null}
          </Card>
        </Pressable>
      ) : null}

      <View style={{ alignItems: 'center', gap: 8, paddingVertical: spacing.md }}>
        <Row gap={8} style={{ alignItems: 'baseline', justifyContent: 'center' }}>
          <TextInput
            value={amount}
            onChangeText={setAmount}
            placeholder="0"
            placeholderTextColor={t.faint}
            keyboardType="decimal-pad"
            accessibilityLabel={`Amount in ${symbol}`}
            // As wide as its figures, so the symbol sits beside them.
            style={[{ color: t.text, fontSize: 52, letterSpacing: -2, ...font.extrabold, textAlign: 'center', width: Math.min(280, Math.max(1, (amount || '0').length) * 30 + 10), padding: 0, outlineWidth: 0 }, tabular]}
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
                {toLabel ?? 'Account'}
              </Text>
              <Text style={{ color: t.faint, fontSize: 12, fontFamily: type.mono.fontFamily }}>{to}</Text>
            </View>
            <Button
              title="Change"
              kind="ghost"
              small
              onPress={() => {
                setTo('');
                setToLabel(null);
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
                    if (isPaymentCode(v) && applyCode(v) === null) return;
                    setTo(v.trim());
                    setToLabel(null);
                    forgetCode();
                  }}
                  placeholder="Account or payment code"
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

      <Segmented options={SCHEDULE.map((s) => ({ value: s.value, label: s.label }))} value={schedule} onChange={setSchedule} />

      {w.user ? (
        <View style={{ paddingHorizontal: 4 }}>
          <FeeRow fee={fee} run={selectedCoin && runFee > 0n ? { amount: runFee, coin: selectedCoin } : undefined} />
        </View>
      ) : null}

      {codeNotice ? (
            <Banner tone="warning">{codeNotice}</Banner>
          ) : coinMismatch ? (
            <Banner tone="warning">The payment code asks for {w.coinById.get(codeCoin!)?.config.symbol ?? shortId(codeCoin!)}; choose that coin to send.</Banner>
          ) : null}
      {problems.length > 0 && (amount || to) ? <Banner tone="warning">{problems.map(sentence).join(' ')}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      {ready ? (
        <Caption style={{ textAlign: 'center', paddingHorizontal: spacing.md }}>
          {willSchedule
            ? `${runsIn(delaySeconds, from?.account.delay ?? 0)}; ${formatAmount((parsed ?? 0n) + runFee, decimals)} ${symbol} is reserved until then.`
            : 'Goes through as soon as a block includes it.'}
          {fee.summary ? ` ${fee.summary}.` : ''}
        </Caption>
      ) : null}
      <Button
        title={`${willSchedule ? 'Schedule' : 'Send'}${parsed && parsed > 0n ? ` ${formatAmount(parsed, decimals)} ${symbol}` : ''}`}
        large
        onPress={send}
        disabled={!ready}
        loading={busy}
      />

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
              setToLabel(r.label);
              forgetCode();
              setPicker(false);
            }}
          />
        ))}
      </Sheet>
    </Screen>
  );
}
