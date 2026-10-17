import React, { useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { equal, hex, isHex, unhex } from '@/chain/bytes';
import { policyFor } from '@/chain/contracts';
import { DELAY_PRESETS, nowSeconds } from '@/chain/format';
import * as idp from '@/chain/identity';
import { SCHEMES, SCHEME_HINT, SCHEME_LABEL, Scheme } from '@/chain/keys';
import { netOf, parseLinkOf } from '@/chain/links';
import { useFee, withFee } from '@/state/fees';
import { DEFAULT_SCHEME, createDeviceKey, keyNameFor } from '@/state/keys';
import { identitySigner, linkedDeviceKeys, proposeChange, userIdentity } from '@/state/registry';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { Avatar, Banner, Body, Button, Caption, Card, Field, ListItem, Pill, Row, Screen, Segmented, Sub } from '@/ui/components';
import { count, describeOperation, nameOf, relativeDuration, sentence } from '@/ui/describe';
import { FeeRow } from '@/ui/FeeRow';
import { spacing } from '@/ui/theme';

type Kind = 'addKey' | 'removeKey' | 'addGuardian' | 'removeGuardian' | 'setThreshold' | 'setDelays' | 'setSlot';

const TITLES: Record<Kind, string> = {
  addKey: 'Add a device',
  removeKey: 'Remove a device',
  addGuardian: 'Link a guardian',
  removeGuardian: 'Unlink a guardian',
  setThreshold: 'Recovery threshold',
  setDelays: 'Delays',
  setSlot: 'Account time lock',
};

/** The changes this screen proposes; anything else in a link is refused. */
const KINDS: Kind[] = ['addKey', 'removeKey', 'addGuardian', 'removeGuardian', 'setThreshold', 'setDelays', 'setSlot'];

/** Changes to how the identity is recovered: applying one voids open recovery proposals. */
const RECOVERY_KINDS: Kind[] = ['addGuardian', 'removeGuardian', 'setThreshold', 'setDelays'];

/** Where the key to add comes from:another device's code, a new key here, or an existing device key (its id). */
type KeySource = 'code' | 'new' | string;

export default function ProposeScreen() {
  // `code`: a device link, scanned or pasted on the wallet.
  const params = useLocalSearchParams<{ kind: Kind; keyId?: string; id?: string; slot?: string; key?: string; code?: string }>();
  const kind: Kind | null = KINDS.includes((params.kind ?? 'addKey') as Kind) ? ((params.kind ?? 'addKey') as Kind) : null;
  const keyIdParam = params.keyId && /^\d+$/.test(params.keyId) ? BigInt(params.keyId) : null;
  const router = useRouter();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const core = w.identity?.core;
  const advanced = w.advanced;

  // A key of this device to add, such as the one of a new recovery phrase.
  const presetKey = params.key && w.keys.some((k) => k.id === params.key) ? params.key : null;
  const addingPhrase = !!presetKey && w.user?.phraseKeyId === presetKey;
  const [source, setSource] = useState<KeySource>(presetKey ?? 'code');
  const [code, setCode] = useState(params.code ?? '');
  const [newKeyName, setNewKeyName] = useState('');
  const [newKeyScheme, setNewKeyScheme] = useState<Scheme>(DEFAULT_SCHEME);
  const [guardian, setGuardian] = useState(params.id && isHex(params.id, 32) ? params.id : '');
  const [threshold, setThreshold] = useState(core ? core.threshold.toString() : '0');
  const [changeDelay, setChangeDelay] = useState(Number(core?.changeDelay ?? idp.DEFAULT_CHANGE_DELAY));
  const [recoveryDelay, setRecoveryDelay] = useState(Number(core?.recoveryDelay ?? idp.DEFAULT_RECOVERY_DELAY));
  const slotNo = params.slot && /^\d+$/.test(params.slot) ? Number(params.slot) : Number(core?.slotCount ?? 1);
  const currentSlot = w.identity?.slots.find((s) => s.slot === slotNo);
  const [slotDelay, setSlotDelay] = useState(currentSlot?.delay ?? 604_800);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fee = useFee(w, null);

  if (kind === null) {
    return (
      <Screen belowHeader title="Propose a change">
        <Banner tone="danger">This is not a change Ting ID can propose.</Banner>
        <Button title="Back" kind="secondary" onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
      </Screen>
    );
  }

  const me = w.user ? userIdentity(w.user) : null;
  // Whether the key to remove is one this device signs with.
  const removingThisDevice =
    keyIdParam !== null && !!w.identity && !!w.info && linkedDeviceKeys(w.info.canonical, w.identity, w.keys).some((k) => k.keyId === keyIdParam);
  const isLinked = (pub: Uint8Array) => !!w.identity?.keys.some((ik) => equal(ik.key.params, pub));
  const availableKeys = w.keys.filter((k) => !isLinked(unhex(k.pub)));
  const otherIdentities = users.map((u) => ({ user: u, id: userIdentity(u) })).filter((x) => x.id && me && !equal(x.id.id, me.id));
  // The device link's key, when it is a link for this identity on this network.
  const device: { scheme: Scheme; pub: Uint8Array } | { problem: string } | null = (() => {
    if (!code.trim()) return null;
    try {
      const link = parseLinkOf(code, 'device');
      if (w.info && link.net !== netOf(w.info.status.chainId)) return { problem: 'this link is for another Ting network' };
      if (me && link.id !== hex(me.id)) return { problem: `this device link is for another identity, not ${w.user?.name ?? 'this one'}` };
      return { scheme: link.scheme, pub: link.key };
    } catch (e) {
      return { problem: (e as Error).message };
    }
  })();
  const parsedCode = device && 'pub' in device ? device : null;

  const operation = (): idp.Operation | string => {
    switch (kind) {
      case 'addKey': {
        if (!w.info) return 'not connected';
        if (source === 'new') return 'built on submit';
        if (source === 'code') {
          if (!parsedCode) return 'paste the device link';
          if (isLinked(parsedCode.pub)) return 'that device already has access';
          return { kind: 'addKey', key: { policy: policyFor(w.info.canonical, parsedCode.scheme), params: parsedCode.pub } };
        }
        const k = w.keys.find((x) => x.id === source);
        return k ? { kind: 'addKey', key: { policy: policyFor(w.info.canonical, k.scheme), params: unhex(k.pub) } } : 'choose a key';
      }
      case 'removeKey':
        return keyIdParam !== null ? { kind: 'removeKey', keyId: keyIdParam } : 'missing key';
      case 'addGuardian':
        return isHex(guardian, 32) ? { kind: 'addGuardian', id: unhex(guardian) } : 'choose a guardian';
      case 'removeGuardian':
        return isHex(guardian, 32) ? { kind: 'removeGuardian', id: unhex(guardian) } : 'missing guardian';
      case 'setThreshold':
        return /^\d+$/.test(threshold) ? { kind: 'setThreshold', threshold: BigInt(threshold) } : 'enter a number';
      case 'setDelays':
        return recoveryDelay < changeDelay ? { kind: 'setDelays', changeDelay: BigInt(changeDelay), recoveryDelay: BigInt(recoveryDelay) } : 'recovery must be shorter than changes';
      case 'setSlot':
        return { kind: 'setSlot', slot: BigInt(slotNo), delay: BigInt(slotDelay) };
    }
  };

  const submit = async () => {
    if (!w.user || !w.info || !w.identity) return;
    setBusy(true);
    setError(null);
    try {
      let op = operation();
      const payer = await fee.resolve();
      if (kind === 'addKey' && source === 'new') {
        const key = await createDeviceKey(newKeyName.trim() || keyNameFor(w.user.name), newKeyScheme);
        op = { kind: 'addKey', key: { policy: policyFor(w.info.canonical, key.scheme), params: unhex(key.pub) } };
      }
      if (typeof op === 'string') throw new Error(op);
      const signer = await identitySigner(w.user, w.info.canonical, w.keys, w.identity);
      const txId = await proposeChange(w.node, w.info.status.chainId, w.identity, signer, [op], payer);
      addSubmitted({ id: txId, at: nowSeconds(), summary: withFee(`Propose: ${describeOperation(op, w.names, w.advanced)}`, fee), userId: w.user.id });
      router.replace({ pathname: '/tx/[id]', params: { id: txId } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!w.identity || !core) {
    return (
      <Screen belowHeader>
        <Banner tone="warning">The identity must be on chain before it can change.</Banner>
      </Screen>
    );
  }
  const preview = operation();
  const wait =
    kind === 'setSlot' && currentSlot && slotDelay < currentSlot.delay
      ? Math.max(Number(core.changeDelay), currentSlot.delay)
      : Number(core.changeDelay);
  const title = addingPhrase ? 'Add a recovery phrase' : advanced && kind === 'addKey' ? 'Add a key' : advanced && kind === 'removeKey' ? 'Remove a key' : TITLES[kind];
  const canSubmit = typeof preview !== 'string' || (kind === 'addKey' && source === 'new');

  return (
    <Screen belowHeader title={title}>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Card>
        {kind === 'addKey' && addingPhrase ? (
          <Caption>Adds the key of your new recovery phrase to the identity. Like every change, it waits before it applies, so a thief cannot add a key of their own at once.</Caption>
        ) : null}
        {kind === 'addKey' && !addingPhrase ? (
          <>
            {advanced ? (
              <ListItem
                title="A device link"
                subtitle="from another phone or browser"
                selected={source === 'code'}
                onPress={() => setSource('code')}
                right={source === 'code' ? <Pill label="selected" tone="primary" small /> : null}
              />
            ) : (
              <Caption>Paste the device link the other device shows after Add a user → Restore → Identity ID, or scan its code from the wallet.</Caption>
            )}
            {source === 'code' ? (
              <View style={{ gap: spacing.sm, paddingLeft: advanced ? spacing.md : 0 }}>
                <Field
                  label="Device link"
                  value={code}
                  onChangeText={setCode}
                  placeholder="ting:device?…"
                  autoCapitalize="none"
                  autoCorrect={false}
                  multiline
                  error={device && 'problem' in device ? sentence(device.problem) : null}
                />
              </View>
            ) : null}
            {advanced ? (
              <>
                <ListItem
                  title="A new key on this device"
                  selected={source === 'new'}
                  onPress={() => setSource('new')}
                  right={source === 'new' ? <Pill label="selected" tone="primary" small /> : null}
                />
                {source === 'new' ? (
                  <View style={{ gap: spacing.sm, paddingLeft: spacing.md }}>
                    <Field label="Name" value={newKeyName} onChangeText={setNewKeyName} placeholder={keyNameFor(w.user?.name ?? 'Me')} />
                    <Segmented options={SCHEMES.map((s) => ({ value: s, label: SCHEME_LABEL[s] }))} value={newKeyScheme} onChange={setNewKeyScheme} />
                    <Caption>{SCHEME_HINT[newKeyScheme]}</Caption>
                  </View>
                ) : null}
                {availableKeys.map((k) => (
                  <ListItem
                    key={k.id}
                    title={k.name}
                    subtitle={`${SCHEME_LABEL[k.scheme]} · already on this device`}
                    selected={source === k.id}
                    onPress={() => setSource(k.id)}
                    right={source === k.id ? <Pill label="selected" tone="primary" small /> : null}
                  />
                ))}
              </>
            ) : null}
          </>
        ) : null}
        {kind === 'removeKey' ? (
          <Body>
            {advanced ? `Remove key ${params.keyId} from this identity.` : removingThisDevice ? 'This device will no longer be able to act for the identity.' : 'That device will no longer be able to act for the identity.'} Make sure another one stays
            usable.
          </Body>
        ) : null}
        {kind === 'addGuardian' ? (
          <>
            <Caption>Pick an identity on this device or paste an ID.</Caption>
            {otherIdentities.map(({ user, id }) => (
              <ListItem
                key={user.id}
                left={<Avatar name={user.name} color={user.color} size={32} />}
                title={user.name}
                selected={guardian === hex(id!.id)}
                onPress={() => setGuardian(hex(id!.id))}
              />
            ))}
            <Field label="Identity ID" value={guardian} onChangeText={setGuardian} autoCapitalize="none" autoCorrect={false} placeholder="64 hex digits" />
          </>
        ) : null}
        {kind === 'removeGuardian' && isHex(guardian, 32) ? <Body>Unlink guardian {nameOf(w.names, unhex(guardian))}.</Body> : null}
        {kind === 'setThreshold' ? (
          <>
            <Field label="Approvals a recovery needs" value={threshold} onChangeText={setThreshold} keyboardType="number-pad" hint="0 means more than half of the guardians." />
            <Caption>Currently {count(core.guardianCount, 'guardian')}.</Caption>
          </>
        ) : null}
        {kind === 'setDelays' ? (
          <>
            <Caption>Changes wait:</Caption>
            <Presets value={changeDelay} onChange={setChangeDelay} min={3600} />
            <Caption>Recovery waits:</Caption>
            <Presets value={recoveryDelay} onChange={setRecoveryDelay} min={60} max={changeDelay - 1} />
          </>
        ) : null}
        {kind === 'setSlot' ? (
          <>
            <Caption>
              {currentSlot ? `Account ${slotNo} currently ${currentSlot.delay ? `waits ${relativeDuration(currentSlot.delay)}` : 'is instant'}.` : `A new account ${slotNo}.`}
            </Caption>
            <Presets value={slotDelay} onChange={setSlotDelay} />
          </>
        ) : null}
      </Card>
      <Card tone="alt">
        <Sub>What happens</Sub>
        <Body>
          {typeof preview === 'string'
            ? kind === 'addKey' && source === 'new'
              ? 'Adds a new key made on this device.'
              : preview
            : kind === 'addKey' && !advanced
              ? 'The other device can act for this identity.'
              : describeOperation(preview, w.names, w.advanced)}
        </Body>
        <Caption>
          Takes effect in {relativeDuration(wait)}; until then any of your devices can cancel it.
          {advanced && kind === 'setSlot' && currentSlot && slotDelay < currentSlot.delay ? ' Lowering a lock waits out the current lock first.' : ''}
          {advanced && RECOVERY_KINDS.includes(kind) ? ' Once it applies, open recovery approvals no longer count.' : ''}
          {advanced ? ' Guardians reaching their threshold for a recovery void it.' : ''}
        </Caption>
        {fee.summary ? <Caption>{fee.summary}.</Caption> : null}
      </Card>
      <FeeRow fee={fee} boxed />
      <Button title="Propose" onPress={submit} loading={busy} disabled={!canSubmit || !!fee.problem} />
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}

function Presets({ value, onChange, min = 0, max }: { value: number; onChange: (v: number) => void; min?: number; max?: number }) {
  const options = DELAY_PRESETS.filter((p) => p.seconds >= min && (max === undefined || p.seconds <= max));
  return (
    <Row style={{ flexWrap: 'wrap' }}>
      {options.map((p) => (
        <Button key={p.seconds} title={p.label} kind={p.seconds === value ? 'primary' : 'ghost'} small onPress={() => onChange(p.seconds)} />
      ))}
    </Row>
  );
}
