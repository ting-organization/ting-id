import React, { useMemo, useState } from 'react';
import { Platform, View } from 'react-native';
import { useRouter } from 'expo-router';
import { hex, isHex, unhex } from '@/chain/bytes';
import { policyFor } from '@/chain/contracts';
import { DELAY_PRESETS, nowSeconds } from '@/chain/format';
import * as idp from '@/chain/identity';
import { SCHEME_LABEL, publicKey } from '@/chain/keys';
import { newPhrase, phraseProblem, phraseSecret, phraseWords, suggestWords } from '@/chain/phrase';
import { fetchIdentity, submitRegistryCall, useChainInfo } from '@/state/chain';
import { findIdentitiesWithKey } from '@/state/identityScan';
import { createDeviceKey, keyNameFor, phraseKey } from '@/state/keys';
import { randomBytes, uuid } from '@/state/keystore';
import { deletePhrase, rememberFresh, savePhrase } from '@/state/phrases';
import { userIdentity } from '@/state/registry';
import { nextColor, useStore } from '@/state/store';
import { Avatar, Banner, Button, Caption, Card, Field, Info, ListItem, Pill, Row, Screen, Segmented, Sub } from '@/ui/components';
import { relativeDuration, sentence } from '@/ui/describe';
import { spacing } from '@/ui/theme';

/**
 * A new identity, with a new recovery phrase; one restored from its recovery
 * phrase; or one restored by its ID, which another device or the guardians
 * hand over.
 */
type Mode = 'new' | 'restore' | 'existing';

/** A restore under way: the chain is read for identities that list the phrase's key. */
type Restore = { stage: 'reading'; height: number; tip: number } | { stage: 'found'; ids: string[] } | { stage: 'none' };

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
  // Restore takes the recovery phrase, or the ID of an identity that another device or the guardians hand over.
  const [top, setTop] = useState<'new' | 'restore'>('new');
  const [restoreBy, setRestoreBy] = useState<'phrase' | 'id'>('phrase');
  const mode: Mode = top === 'restore' ? (restoreBy === 'id' ? 'existing' : 'restore') : top;
  const [phraseText, setPhraseText] = useState('');
  const [restore, setRestore] = useState<Restore | null>(null);
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
  const modes: { value: typeof top; label: string }[] = [
    { value: 'new', label: 'New identity' },
    { value: 'restore', label: 'Restore' },
  ];

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const userName = name.trim() || 'Me';
      const color = nextColor(users);
      if (!info) throw new Error(nodeError ?? 'the node is not reachable');
      const c = info.canonical;
      if (mode === 'existing') {
        const h = existingId.trim().toLowerCase();
        if (!isHex(h, 32)) throw new Error('an identity ID is 64 hex digits');
        if (!(await fetchIdentity(node, c.registry, unhex(h)))) throw new Error('no identity with that ID on this chain');
        // This device's key for the identity; another device adds it, or a recovery installs it.
        const key = await createDeviceKey(keyNameFor(userName));
        const id = uuid();
        addUser({ id, name: userName, color, kind: 'identity', identityId: h, registry: hex(c.registry), chainId: hex(info.status.chainId), keyId: key.id });
        setActive(id);
        router.replace('/identity');
        return;
      }
      // The identity's first key is its recovery phrase's, kept on this device.
      const words = newPhrase(randomBytes);
      const key = await phraseKey(keyNameFor(userName), words);
      const idKeys: idp.IdKey[] = [key, ...keys.filter((k) => extraKeys.includes(k.id) && k.id !== key.id)].map((k) => ({
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
      const phrase = await savePhrase(id, words);
      const user = {
        id,
        name: userName,
        color,
        kind: 'identity' as const,
        identityId: hex(identityId),
        registry: hex(c.registry),
        chainId: hex(info.status.chainId),
        creation: hex(creation),
        keyId: key.id,
        phrase,
        phraseKeyId: key.id,
        phraseBackedUp: false,
      };
      addUser(user);
      let txId: string;
      try {
        txId = await submitRegistryCall(node, c.registry, idp.createInput(creation));
      } catch (e) {
        removeUser(id);
        await deletePhrase(user).catch(() => {});
        throw e;
      }
      setActive(id);
      addSubmitted({ id: txId, at: nowSeconds(), summary: `Create identity ${userName}`, userId: id });
      // The words first: the person writes them down while a block includes the identity.
      rememberFresh(id, words);
      router.replace({ pathname: '/phrase/[id]', params: { id } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** Reads the phrase's key, then the chain for the identities that list it. */
  const findRestore = async () => {
    const problem = phraseProblem(phraseText);
    if (problem) return setError(problem);
    if (!info) return setError(nodeError ?? 'the node is not reachable');
    setBusy(true);
    setError(null);
    setRestore({ stage: 'reading', height: 0, tip: info.status.height });
    try {
      const pub = publicKey('secp256k1', await phraseSecret(phraseText));
      const c = info.canonical;
      const ids = await findIdentitiesWithKey(node, c.registry, { policy: policyFor(c, 'secp256k1'), params: pub }, (height, tip) =>
        setRestore({ stage: 'reading', height, tip }),
      );
      setRestore(ids.length > 0 ? { stage: 'found', ids: ids.map(hex) } : { stage: 'none' });
    } catch (e) {
      setRestore(null);
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** Adds the restored identity. */
  const finishRestore = async (identityHex: string) => {
    if (!info) return;
    setBusy(true);
    setError(null);
    try {
      const userName = name.trim() || 'Me';
      const key = await phraseKey(keyNameFor(userName), phraseText);
      const id = uuid();
      const phrase = await savePhrase(id, phraseText);
      // Restored from the words: they are written down already.
      const kept = { keyId: key.id, phrase, phraseKeyId: key.id, phraseBackedUp: true };
      addUser({ id, name: userName, color: nextColor(users), kind: 'identity', identityId: identityHex, registry: hex(info.canonical.registry), chainId: hex(info.status.chainId), ...kept });
      setActive(id);
      setPhraseText('');
      router.replace('/identity');
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
        <Segmented options={modes} value={top} onChange={setTop} />
        {top === 'restore' ? (
          <Segmented
            options={[
              { value: 'phrase', label: 'Recovery phrase' },
              { value: 'id', label: 'Identity ID' },
            ]}
            value={restoreBy}
            onChange={setRestoreBy}
          />
        ) : null}
        <Caption>
          {mode === 'new'
            ? 'An instant spending account, a time-locked savings vault, a recovery phrase, and guardians who can restore access.'
            : mode === 'restore'
              ? 'Type or paste the 12 to 24 words of your recovery phrase. Ting ID finds the identity that lists their key.'
              : 'Paste the ID of your identity. A device that has it adds this one, or your guardians recover it here with a new recovery phrase.'}
        </Caption>
        {mode === 'existing' ? (
          <Field label="Identity ID" value={existingId} onChangeText={(v) => setExistingId(v.trim())} placeholder="64 hex digits" autoCapitalize="none" autoCorrect={false} />
        ) : null}
      </Card>

      {mode === 'restore' ? (
        <RestoreCard
          text={phraseText}
          onText={(v) => {
            setPhraseText(v);
            setRestore(null);
          }}
          restore={restore}
          busy={busy}
          onFind={findRestore}
          onFinish={finishRestore}
        />
      ) : null}

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

      {advanced && mode === 'new' ? (
        <Card>
          <Sub>Keys</Sub>
          <Caption>The recovery phrase gives the identity its first key, a secp256k1 key on this device.</Caption>
          {keys.length > 0 ? (
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

      {mode !== 'restore' ? (
        <Button
          title={mode === 'new' ? 'Create identity' : 'Add identity'}
          onPress={create}
          loading={busy}
          disabled={!info || (mode === 'existing' && !isHex(existingId, 32))}
        />
      ) : null}
      {mode === 'new' ? <Caption>Next you write down its recovery phrase. The identity can receive funds as soon as it is created.</Caption> : null}
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

/**
 * The words of a restore, with the list's words suggested for the one being
 * typed. Keyboards are told not to learn or suggest them: they are a secret.
 */
function RestoreCard({
  text,
  onText,
  restore,
  busy,
  onFind,
  onFinish,
}: {
  text: string;
  onText: (v: string) => void;
  restore: Restore | null;
  busy: boolean;
  onFind: () => void;
  onFinish: (identityHex: string) => void;
}) {
  const words = phraseWords(text);
  const typing = text.endsWith(' ') ? '' : (words[words.length - 1] ?? '');
  const suggestions = typing ? suggestWords(typing).filter((w) => w !== typing) : [];
  const problem = words.length >= 12 ? phraseProblem(text) : null;
  return (
    <Card>
      <Sub>Recovery phrase</Sub>
      <Field
        value={text}
        onChangeText={onText}
        placeholder="word word word …"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="off"
        importantForAutofill="no"
        spellCheck={false}
        keyboardType={Platform.OS === 'android' ? 'visible-password' : 'default'}
        multiline={Platform.OS !== 'android'}
        error={problem ? sentence(problem) : null}
        hint={`${words.length} ${words.length === 1 ? 'word' : 'words'}`}
      />
      {suggestions.length > 0 ? (
        <Row gap={6} style={{ flexWrap: 'wrap' }}>
          {suggestions.map((w) => (
            <Button key={w} title={w} kind="secondary" small onPress={() => onText(`${[...words.slice(0, -1), w].join(' ')} `)} />
          ))}
        </Row>
      ) : null}
      {restore?.stage === 'reading' ? (
        <Caption>
          Looking for the identity · block {restore.height} of {restore.tip}
        </Caption>
      ) : null}
      {restore?.stage === 'found' ? (
        <View style={{ gap: spacing.sm }}>
          <Caption>{restore.ids.length === 1 ? 'This phrase belongs to an identity:' : 'This phrase belongs to more than one identity. Choose one:'}</Caption>
          {restore.ids.map((id) => (
            <ListItem key={id} title={`Identity ${id.slice(0, 8)}…${id.slice(-6)}`} subtitle="its keys list this phrase’s key" chevron onPress={() => onFinish(id)} />
          ))}
        </View>
      ) : null}
      {restore?.stage === 'none' ? (
        <Banner tone="warning">No identity on this network lists this phrase’s key. A recovery or a key change may have replaced it.</Banner>
      ) : null}
      {restore?.stage !== 'found' ? <Button title="Find my identity" onPress={onFind} loading={busy} disabled={words.length < 12} /> : null}
    </Card>
  );
}
