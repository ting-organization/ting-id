import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { equal, hex, isHex, unhex } from '@/core/chain/bytes';
import { policyFor } from '@/core/chain/contracts';
import { nowSeconds } from '@/core/chain/format';
import * as idp from '@/core/chain/identity';
import { SCHEMES, SCHEME_HINT, SCHEME_LABEL, Scheme, parseDeviceCode } from '@/core/chain/keys';
import { useFee, withFee } from '@/core/state/fees';
import { DEFAULT_SCHEME, createDeviceKey, keyNameFor } from '@/core/state/keys';
import { identitySigner, linkedDeviceKeys, proposeChange, userIdentity } from '@/core/state/registry';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { count, describeOperation, nameOf, relativeDuration, sentence } from '@/core/describe';
import { Avatar, Banner, Body, Button, Caption, Card, Dialog, Field, ListItem, Pill, Segmented, Sub } from '@/ui/components';
import { FeeRow } from '@/ui/FeeRow';
import { useModal } from '@/ui/modal';
import { Presets } from './NewUser';

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

/** Changes to how the identity is recovered: applying one voids open recovery proposals. */
const RECOVERY_KINDS: Kind[] = ['addGuardian', 'removeGuardian', 'setThreshold', 'setDelays'];

/** Where the key to add comes from: another device's code, a new key here, or an existing device key (its id). */
type KeySource = 'code' | 'new' | string;

/** The changes this dialog proposes; anything else in an address is refused. */
const KINDS: Kind[] = ['addKey', 'removeKey', 'addGuardian', 'removeGuardian', 'setThreshold', 'setDelays', 'setSlot'];

export function ProposeDialog() {
  const modal = useModal();
  const [params] = useSearchParams();
  const asked = (params.get('kind') ?? 'addKey') as Kind;
  const kind: Kind | null = KINDS.includes(asked) ? asked : null;
  const keyIdParam = /^\d+$/.test(params.get('keyId') ?? '') ? BigInt(params.get('keyId')!) : null;
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const core = w.identity?.core;
  const advanced = w.advanced;

  const [source, setSource] = useState<KeySource>('code');
  const [code, setCode] = useState('');
  const [newKeyName, setNewKeyName] = useState('');
  const [newKeyScheme, setNewKeyScheme] = useState<Scheme>(DEFAULT_SCHEME);
  const [guardian, setGuardian] = useState(isHex(params.get('id') ?? '', 32) ? params.get('id')! : '');
  const [threshold, setThreshold] = useState(core ? core.threshold.toString() : '0');
  const [changeDelay, setChangeDelay] = useState(Number(core?.changeDelay ?? idp.DEFAULT_CHANGE_DELAY));
  const [recoveryDelay, setRecoveryDelay] = useState(Number(core?.recoveryDelay ?? idp.DEFAULT_RECOVERY_DELAY));
  const slotNo = /^\d+$/.test(params.get('slot') ?? '') ? Number(params.get('slot')) : Number(core?.slotCount ?? 1);
  const currentSlot = w.identity?.slots.find((s) => s.slot === slotNo);
  const [slotDelay, setSlotDelay] = useState(currentSlot?.delay ?? 604_800);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fee = useFee(w, null);

  if (kind === null) {
    return (
      <Dialog open onClose={modal.close} busy={busy} title="Propose a change">
        <Banner tone="danger">This is not a change Ting ID can propose.</Banner>
      </Dialog>
    );
  }

  const me = w.user ? userIdentity(w.user) : null;
  // Whether the key to remove is one this device signs with.
  const removingThisDevice =
    keyIdParam !== null && !!w.identity && !!w.info && linkedDeviceKeys(w.info.canonical, w.identity, w.keys).some((k) => k.keyId === keyIdParam);
  const isLinked = (pub: Uint8Array) => !!w.identity?.keys.some((ik) => equal(ik.key.params, pub));
  const availableKeys = w.keys.filter((k) => !isLinked(unhex(k.pub)));
  const otherIdentities = users.map((u) => ({ user: u, id: userIdentity(u) })).filter((x) => x.id && me && !equal(x.id.id, me.id));
  const parsedCode = code.trim() ? parseDeviceCode(code) : null;

  const operation = (): idp.Operation | string => {
    switch (kind) {
      case 'addKey': {
        if (!w.info) return 'not connected';
        if (source === 'new') return 'built on submit';
        if (source === 'code') {
          if (!parsedCode) return 'paste the device code';
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
      modal.open(`/tx/${txId}`, true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!w.identity || !core) {
    return (
      <Dialog open onClose={modal.close} busy={busy} title="Propose a change">
        <Banner tone="warning">The identity must be on chain before it can change.</Banner>
      </Dialog>
    );
  }
  const preview = operation();
  const wait =
    kind === 'setSlot' && currentSlot && slotDelay < currentSlot.delay
      ? Math.max(Number(core.changeDelay), currentSlot.delay)
      : Number(core.changeDelay);
  const title = advanced && kind === 'addKey' ? 'Add a key' : advanced && kind === 'removeKey' ? 'Remove a key' : TITLES[kind];
  const canSubmit = typeof preview !== 'string' || (kind === 'addKey' && source === 'new');

  return (
    <Dialog open onClose={modal.close} busy={busy} title={title}>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Card>
        {kind === 'addKey' ? (
          <>
            {advanced ? (
              <ListItem
                first
                title="A device code"
                subtitle="from another phone or computer"
                selected={source === 'code'}
                onClick={() => setSource('code')}
                right={source === 'code' ? <Pill label="selected" tone="primary" small /> : null}
              />
            ) : (
              <Caption>Paste the device code shown on the other device under Add a user → Existing identity.</Caption>
            )}
            {source === 'code' ? (
              <Field label="Device code" value={code} onChangeText={setCode} placeholder="secp256k1:04…" multiline error={code.trim() && !parsedCode ? 'That is not a device code.' : null} />
            ) : null}
            {advanced ? (
              <>
                <ListItem first title="A new key on this computer" selected={source === 'new'} onClick={() => setSource('new')} right={source === 'new' ? <Pill label="selected" tone="primary" small /> : null} />
                {source === 'new' ? (
                  <div className="stack" style={{ paddingLeft: 12 }}>
                    <Field label="Name" value={newKeyName} onChangeText={setNewKeyName} placeholder={keyNameFor(w.user?.name ?? 'Me')} />
                    <Segmented options={SCHEMES.map((s) => ({ value: s, label: SCHEME_LABEL[s] }))} value={newKeyScheme} onChange={setNewKeyScheme} />
                    <Caption>{SCHEME_HINT[newKeyScheme]}</Caption>
                  </div>
                ) : null}
                {availableKeys.map((k) => (
                  <ListItem
                    key={k.id}
                    first
                    title={k.name}
                    subtitle={`${SCHEME_LABEL[k.scheme]} · already on this computer`}
                    selected={source === k.id}
                    onClick={() => setSource(k.id)}
                    right={source === k.id ? <Pill label="selected" tone="primary" small /> : null}
                  />
                ))}
              </>
            ) : null}
          </>
        ) : null}
        {kind === 'removeKey' ? (
          <Body>
            {advanced ? `Remove key ${params.get('keyId')} from this identity.` : removingThisDevice ? 'This device will no longer be able to act for the identity.' : 'That device will no longer be able to act for the identity.'} Make sure another one
            stays usable.
          </Body>
        ) : null}
        {kind === 'addGuardian' ? (
          <>
            <Caption>Pick an identity on this computer or paste an ID.</Caption>
            {otherIdentities.map(({ user, id }) => (
              <ListItem key={user.id} first left={<Avatar name={user.name} color={user.color} size={32} />} title={user.name} selected={guardian === hex(id!.id)} onClick={() => setGuardian(hex(id!.id))} />
            ))}
            <Field label="Identity ID" value={guardian} onChangeText={setGuardian} placeholder="64 hex digits" mono />
          </>
        ) : null}
        {kind === 'removeGuardian' && isHex(guardian, 32) ? <Body>Unlink guardian {nameOf(w.names, unhex(guardian))}.</Body> : null}
        {kind === 'setThreshold' ? (
          <>
            <Field label="Approvals a recovery needs" value={threshold} onChangeText={setThreshold} hint="0 means more than half of the guardians." />
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
            <Caption>{currentSlot ? `Account ${slotNo} currently ${currentSlot.delay ? `waits ${relativeDuration(currentSlot.delay)}` : 'is instant'}.` : `A new account ${slotNo}.`}</Caption>
            <Presets value={slotDelay} onChange={setSlotDelay} />
          </>
        ) : null}
      </Card>
      <Card tone="alt">
        <Sub>What happens</Sub>
        <Body>
          {typeof preview === 'string'
            ? kind === 'addKey' && source === 'new'
              ? 'Adds a new key made on this computer.'
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
      <Button title="Propose" onClick={submit} loading={busy} disabled={!canSubmit || !!fee.problem} block />
    </Dialog>
  );
}
