import React, { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { hex, isHex, unhex } from '@/chain/bytes';
import { policyFor } from '@/chain/contracts';
import { DELAY_PRESETS, nowSeconds } from '@/chain/format';
import * as idp from '@/chain/identity';
import { SCHEMES, SCHEME_HINT, SCHEME_LABEL, Scheme } from '@/chain/keys';
import { fetchIdentity, submitRegistryCall, useChainInfo } from '@/state/chain';
import { DEFAULT_SCHEME, createDeviceKey, keyNameFor } from '@/state/keys';
import { randomBytes, uuid } from '@/state/keystore';
import { userIdentity } from '@/state/registry';
import { nextColor, useStore } from '@/state/store';
import { Avatar, Banner, Button, Caption, Card, Field, Info, ListItem, Pill, Row, Screen, Segmented, Sub } from '@/ui/components';
import { relativeDuration, sentence } from '@/ui/describe';
import { spacing } from '@/ui/theme';

/** A new identity, an identity that exists already (another device of it), or a bare key account. */
type Mode = 'new' | 'existing' | 'simple';

export default function NewUserScreen() {
  const router = useRouter();
  const { info, node, error: nodeError } = useChainInfo();
  const users = useStore((s) => s.users);
  const keys = useStore((s) => s.keys);
  const advanced = useStore((s) => s.settings.advanced);
  const addUser = useStore((s) => s.addUser);
  const removeUser = useStore((s) => s.removeUser);
  const setActive = useStore((s) => s.setActiveUser);
  const addSubmitted = useStore((s) => s.addSubmitted);

  const [name, setName] = useState('');
  const [mode, setMode] = useState<Mode>('new');
  const [scheme, setScheme] = useState<Scheme>(DEFAULT_SCHEME);
  const [extraKeys, setExtraKeys] = useState<string[]>([]);
  const [existingId, setExistingId] = useState('');
  const [guardians, setGuardians] = useState<string[]>([]);
  const [guardianHex, setGuardianHex] = useState('');
  const [threshold, setThreshold] = useState('0');
  const [locks, setLocks] = useState(false);
  const [changeDelay, setChangeDelay] = useState(idp.DEFAULT_CHANGE_DELAY);
  const [recoveryDelay, setRecoveryDelay] = useState(idp.DEFAULT_RECOVERY_DELAY);
  const [savingsDelay, setSavingsDelay] = useState(idp.DEFAULT_SLOTS[1]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const otherIdentities = useMemo(() => users.map((u) => ({ user: u, id: userIdentity(u) })).filter((x) => x.id !== null), [users]);
  const modes: { value: Mode; label: string }[] = [
    { value: 'new', label: 'New identity' },
    { value: 'existing', label: 'Existing identity' },
    ...(advanced ? [{ value: 'simple' as Mode, label: 'Simple account' }] : []),
  ];

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const userName = name.trim() || (mode === 'simple' ? 'Account' : 'Me');
      const color = nextColor(users);
      if (mode === 'simple') {
        const key = await createDeviceKey(keyNameFor(userName), scheme);
        const id = uuid();
        addUser({ id, name: userName, color, kind: 'key', keyId: key.id });
        setActive(id);
        if (router.canGoBack()) router.back();
        else router.replace('/');
        return;
      }
      if (!info) throw new Error(nodeError ?? 'the node is not reachable');
      const c = info.canonical;
      if (mode === 'existing') {
        const h = existingId.trim().toLowerCase();
        if (!isHex(h, 32)) throw new Error('an identity ID is 64 hex digits');
        if (!(await fetchIdentity(node, c.registry, unhex(h)))) throw new Error('no identity with that id on this chain');
        // This device's key for the identity; another device adds it, or a recovery installs it.
        const key = await createDeviceKey(keyNameFor(userName));
        const id = uuid();
        addUser({ id, name: userName, color, kind: 'identity', identityId: h, registry: hex(c.registry), chainId: hex(info.status.chainId), keyId: key.id });
        setActive(id);
        router.replace('/identity');
        return;
      }
      // One fresh key for the new identity, on this device.
      const key = await createDeviceKey(keyNameFor(userName), scheme);
      const idKeys: idp.IdKey[] = [key, ...keys.filter((k) => extraKeys.includes(k.id))].map((k) => ({
        policy: policyFor(c, k.scheme),
        params: unhex(k.pub),
      }));
      const settings: idp.Settings = {
        keys: idKeys,
        // The registry wants them in byte order, and the id is the hash of the creation input.
        guardians: idp.sortGuardians(guardians.map(unhex)),
        threshold: Number(threshold) || 0,
        changeDelay,
        recoveryDelay,
        slots: [0, savingsDelay],
        salt: randomBytes(32),
      };
      const creation = idp.encodeCreation(settings);
      const identityId = idp.idOf(creation);
      const problem = idp.validSettings(settings, identityId);
      if (problem) throw new Error(problem);
      // The user is saved first: an identity on chain that no user here names would be lost from view.
      const id = uuid();
      addUser({
        id,
        name: userName,
        color,
        kind: 'identity',
        identityId: hex(identityId),
        registry: hex(c.registry),
        chainId: hex(info.status.chainId),
        creation: hex(creation),
        keyId: key.id,
      });
      let txId: string;
      try {
        txId = await submitRegistryCall(node, c.registry, idp.createInput(creation));
      } catch (e) {
        removeUser(id);
        throw e;
      }
      setActive(id);
      addSubmitted({ id: txId, at: nowSeconds(), summary: `Create identity ${userName}`, userId: id });
      router.replace({ pathname: '/tx/[id]', params: { id: txId } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen belowHeader>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Card>
        <Field label="Name" value={name} onChangeText={setName} placeholder="Your name" autoFocus />
        <Segmented options={modes} value={mode} onChange={setMode} />
        <Caption>
          {mode === 'new'
            ? 'An instant spending account, a time-locked savings vault, and guardians who can restore access.'
            : mode === 'existing'
              ? 'Paste the ID of an identity you already have. A device that has access adds this one, or your guardians can recover it here.'
              : 'One key and one account, without guardians or recovery.'}
        </Caption>
        {mode === 'existing' ? (
          <Field label="Identity ID" value={existingId} onChangeText={(v) => setExistingId(v.trim())} placeholder="64 hex digits" autoCapitalize="none" autoCorrect={false} />
        ) : null}
      </Card>

      {mode === 'new' ? (
        <>
          <Card>
            <Row gap={6}>
              <Sub>Guardians</Sub>
              <Info title="Guardians" text="People you trust to restore your access. A majority of them must agree; none can move your funds." />
            </Row>
            <Caption>Optional: you can add them later.</Caption>
            {otherIdentities.map(({ user, id }) => {
              const h = hex(id!.id);
              const on = guardians.includes(h);
              return (
                <ListItem
                  key={user.id}
                  left={<Avatar name={user.name} color={user.color} size={32} />}
                  title={user.name}
                  subtitle="identity on this device"
                  selected={on}
                  onPress={() => setGuardians((g) => (on ? g.filter((x) => x !== h) : [...g, h]))}
                  right={on ? <Pill label="guardian" tone="primary" small /> : null}
                />
              );
            })}
            {guardians
              .filter((g) => !otherIdentities.some((o) => hex(o.id!.id) === g))
              .map((g) => (
                <ListItem key={g} title={`${g.slice(0, 12)}…`} subtitle="pasted ID" right={<Button title="Remove" kind="ghost" small onPress={() => setGuardians((x) => x.filter((y) => y !== g))} />} />
              ))}
            <Row>
              <View style={{ flex: 1 }}>
                <Field value={guardianHex} onChangeText={setGuardianHex} placeholder="paste an identity ID" autoCapitalize="none" autoCorrect={false} />
              </View>
              <Button
                title="Add"
                kind="secondary"
                small
                disabled={!isHex(guardianHex, 32)}
                onPress={() => {
                  const pasted = guardianHex.trim().toLowerCase();
                  setGuardians((g) => (g.includes(pasted) ? g : [...g, pasted]));
                  setGuardianHex('');
                }}
              />
            </Row>
            {advanced ? <Field label="Approvals needed (0 = more than half)" value={threshold} onChangeText={setThreshold} keyboardType="number-pad" /> : null}
          </Card>

          {/* Everyone else gets the defaults: savings and changes wait 7 days, recovery 2. */}
          {advanced ? (
            <Card>
              <Row style={{ justifyContent: 'space-between' }}>
                <Sub>Time locks</Sub>
                <Button title={locks ? 'Done' : 'Change'} kind="ghost" small onPress={() => setLocks((l) => !l)} />
              </Row>
              {locks ? (
                <>
                  <Caption>Savings vault: withdrawals wait this long and can be cancelled meanwhile.</Caption>
                  <Presets value={savingsDelay} onChange={setSavingsDelay} min={60} />
                  <Caption>Changes to devices, guardians and settings wait:</Caption>
                  <Presets value={changeDelay} onChange={setChangeDelay} min={3600} />
                  <Caption>Guardians hand over the identity after:</Caption>
                  <Presets value={recoveryDelay} onChange={setRecoveryDelay} min={60} max={changeDelay - 1} />
                  <Caption>
                    Recovery ({relativeDuration(recoveryDelay)}) must be shorter than changes ({relativeDuration(changeDelay)}), so guardians can undo
                    what a thief starts.
                  </Caption>
                </>
              ) : (
                <Caption>
                  Savings withdrawals wait {relativeDuration(savingsDelay)}; changes to the identity wait {relativeDuration(changeDelay)}; guardians can
                  hand it over after {relativeDuration(recoveryDelay)}. Everything stays cancellable while it waits.
                </Caption>
              )}
            </Card>
          ) : null}
        </>
      ) : null}

      {advanced && mode !== 'existing' ? (
        <Card>
          <Sub>Key on this device</Sub>
          <Segmented options={SCHEMES.map((s) => ({ value: s, label: SCHEME_LABEL[s] }))} value={scheme} onChange={setScheme} />
          <Caption>{SCHEME_HINT[scheme]}</Caption>
          {mode === 'new' && keys.length > 0 ? (
            <View style={{ gap: 4 }}>
              <Caption>Also sign with existing keys:</Caption>
              {keys.map((k) => (
                <ListItem
                  key={k.id}
                  title={k.name}
                  subtitle={SCHEME_LABEL[k.scheme]}
                  selected={extraKeys.includes(k.id)}
                  onPress={() => setExtraKeys((e) => (e.includes(k.id) ? e.filter((x) => x !== k.id) : [...e, k.id]))}
                  right={extraKeys.includes(k.id) ? <Pill label="included" tone="primary" small /> : null}
                />
              ))}
            </View>
          ) : null}
        </Card>
      ) : null}

      <Button
        title={mode === 'new' ? 'Create identity' : mode === 'existing' ? 'Add identity' : 'Create account'}
        onPress={create}
        loading={busy}
        disabled={(mode !== 'simple' && !info) || (mode === 'existing' && !isHex(existingId, 32))}
      />
      {mode === 'new' ? <Caption>The identity can receive funds as soon as it is created.</Caption> : null}
      {mode === 'existing' ? <Caption>The Identity tab then shows a device code for your other device.</Caption> : null}
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
