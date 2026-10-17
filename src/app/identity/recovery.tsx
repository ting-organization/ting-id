import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { equal, hex, isHex, unhex } from '@/chain/bytes';
import { policyFor, schemeOf } from '@/chain/contracts';
import { nowSeconds, relativeTime } from '@/chain/format';
import * as idp from '@/chain/identity';
import { SCHEME_LABEL } from '@/chain/keys';
import { encodeLink, netOf, parseLinkOf } from '@/chain/links';
import { fetchIdentity, IdentityInfo } from '@/state/chain';
import { useFee, withFee } from '@/state/fees';
import { deviceKeyOf, newUserPhrase } from '@/state/keys';
import { ProposalStatus, approveRecovery, identitySigner, linkedDeviceKeys, proposalStatus, recover, userIdentity, withdrawApproval } from '@/state/registry';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { Banner, Button, Caption, Card, Field, ListItem, Pill, Row, Screen, Segmented, Sub } from '@/ui/components';
import { keyFingerprint, nameOf, relativeDuration, sentence } from '@/ui/describe';
import { FeeRow } from '@/ui/FeeRow';
import { LinkShare } from '@/ui/Qr';
import { spacing, useTheme } from '@/ui/theme';

type Mode = 'mine' | 'help';

export default function RecoveryScreen() {
  const t = useTheme();
  // `code`: a recovery link, scanned or pasted on the wallet.
  const params = useLocalSearchParams<{ mode?: Mode; code?: string }>();
  const router = useRouter();
  const w = useWorkspace();
  const addSubmitted = useStore((s) => s.addSubmitted);
  const advanced = w.advanced;
  // The key this device made for the user, or failing that one that already signs for it.
  const deviceKey = w.user ? (deviceKeyOf(w.user, w.keys) ?? (w.identity && w.info ? (linkedDeviceKeys(w.info.canonical, w.identity, w.keys)[0]?.key ?? null) : null)) : null;
  const [mode, setMode] = useState<Mode>(params.mode ?? 'mine');
  const [selectedKeys, setSelectedKeys] = useState<string[]>(() => (deviceKey ? [deviceKey.id] : []));
  const [linkText, setLinkText] = useState(params.code ?? '');
  const [lookup, setLookup] = useState<{ key: string; subject: string; info: IdentityInfo | null; status: ProposalStatus | null } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Paid from the acting user's own instant account: the guardian's, when approving.
  const fee = useFee(w, null);
  const me = w.user ? userIdentity(w.user) : null;

  // "Mine": the identity's keys become the key of a new recovery phrase, as the
  // old words may be what was lost or stolen; in advanced mode, the keys picked here.
  const phraseKey = w.user ? (w.keys.find((k) => k.id === w.user!.phraseKeyId) ?? null) : null;
  const phraseListed = !!phraseKey && !!w.identity && !!w.info && linkedDeviceKeys(w.info.canonical, w.identity, [phraseKey]).length > 0;
  const recoveryKey = phraseKey && !phraseListed ? phraseKey : null;
  const chosen = advanced ? w.keys.filter((k) => selectedKeys.includes(k.id)) : recoveryKey ? [recoveryKey] : [];
  const [preparing, setPreparing] = useState(false);
  const prepare = async () => {
    if (!w.user) return;
    setPreparing(true);
    setError(null);
    try {
      await newUserPhrase(w.user);
      router.push({ pathname: '/phrase/[id]', params: { id: w.user.id } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPreparing(false);
    }
  };
  const myKeyList = (() => {
    if (!w.info) return null;
    const keys = chosen.map((k) => ({ policy: policyFor(w.info!.canonical, k.scheme), params: unhex(k.pub) }));
    return keys.length ? idp.encodeKeyList(keys) : null;
  })();

  // "Help": the recovering identity and its new keys, from the link it sent.
  const recoverLink: { id: string; keys: Uint8Array } | { problem: string } | null = (() => {
    if (!linkText.trim()) return null;
    try {
      const link = parseLinkOf(linkText, 'recover');
      if (w.info && link.net !== netOf(w.info.status.chainId)) return { problem: 'this link is for another Ting network' };
      if (!idp.decodeKeyList(link.keys)) return { problem: 'the link lists no keys this app can read' };
      return { id: link.id, keys: link.keys };
    } catch (e) {
      return { problem: (e as Error).message };
    }
  })();
  const target = recoverLink && 'id' in recoverLink ? recoverLink.id : '';
  const keyListHex = recoverLink && 'keys' in recoverLink ? hex(recoverLink.keys) : '';
  const targetId = mode === 'mine' ? (me?.id ?? null) : isHex(target, 32) ? unhex(target) : null;
  const keyList = mode === 'mine' ? myKeyList : isHex(keyListHex) && keyListHex.length > 0 ? unhex(keyListHex) : null;

  // A guardian helping someone also learns where it stands on the proposal.
  const guardianId = mode === 'help' && w.identity ? w.identity.id : null;
  // What is looked up, read again with each block; the last answer for it shows meanwhile.
  const subject = targetId ? `${hex(targetId)}:${keyList ? hex(keyList) : ''}:${guardianId ? hex(guardianId) : ''}` : null;
  const lookupKey = w.info && subject ? `${w.info.status.height}:${subject}` : null;
  const current = lookup && lookup.subject === subject ? lookup : null;
  const targetInfo = current?.info ?? null;
  const status = current?.status ?? null;

  useEffect(() => {
    let alive = true;
    if (!w.info || !targetId || !lookupKey) return;
    const registry = w.info.canonical.registry;
    fetchIdentity(w.node, registry, targetId)
      .then(async (info) => {
        if (!alive) return;
        const valid = keyList && idp.decodeKeyList(keyList) ? keyList : null;
        const s = info && valid ? await proposalStatus(w.node, registry, targetId, info.core, valid, guardianId ?? undefined) : null;
        if (alive) setLookup({ key: lookupKey, subject: subject!, info, status: s });
      })
      .catch((e) => alive && setError((e as Error).message));
    return () => {
      alive = false;
    };
  }, [w.node, lookupKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // One action at a time: a second button pressed meanwhile does nothing.
  const running = useRef(false);
  const act = async (label: string, summary: string, f: () => Promise<string>) => {
    if (running.current) return;
    running.current = true;
    setBusy(label);
    setError(null);
    try {
      const txId = await f();
      if (w.user) addSubmitted({ id: txId, at: nowSeconds(), summary: withFee(summary, fee), userId: w.user.id });
      router.push({ pathname: '/tx/[id]', params: { id: txId } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      running.current = false;
      setBusy(null);
    }
  };

  // An approval carries the key list (the registry derives the proposal from it); a withdrawal names the proposal,
  // this one or another this guardian approves, since a guardian approves one proposal at a time.
  const approval = (approve: boolean, other?: Uint8Array) =>
    act(approve ? 'approve' : other ? 'withdraw-other' : 'withdraw', `${approve ? 'Approve' : 'Withdraw'} recovery of ${isHex(target, 32) ? nameOf(w.names, unhex(target)) : 'an identity'}`, async () => {
      if (!w.user || !w.info || !w.identity || !targetId || !status || !keyList) throw new Error('not ready');
      const signer = await identitySigner(w.user, w.info.canonical, w.keys, w.identity);
      const registry = w.info.canonical.registry;
      const payer = await fee.resolve();
      return approve
        ? approveRecovery(w.node, w.info.status.chainId, registry, w.identity, signer, targetId, keyList, status.proposal, payer)
        : withdrawApproval(w.node, w.info.status.chainId, registry, w.identity, signer, targetId, other ?? status.proposal, payer);
    });

  // The keys the recovery installs, so a guardian can compare them with what the person sees.
  const newKeys = keyList ? idp.decodeKeyList(keyList) : null;
  const keyLabel = (k: idp.IdKey) => {
    const kind = w.info ? schemeOf(w.info.canonical, k.policy) : null;
    const scheme = kind === 'identity' ? 'another identity' : kind === 'multisig' ? 'multisig' : kind ? SCHEME_LABEL[kind] : 'custom policy';
    return w.keys.some((d) => equal(unhex(d.pub), k.params)) ? `${scheme} · on this device` : scheme;
  };

  const due = status?.executableAt !== null && status?.executableAt !== undefined && Number(status.executableAt) <= nowSeconds();

  return (
    <Screen belowHeader>
      <Segmented
        options={[
          { value: 'mine', label: 'Recover mine' },
          { value: 'help', label: 'Help someone' },
        ]}
        value={mode}
        onChange={setMode}
      />
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}

      {mode === 'mine' ? (
        <>
          {!me ? <Banner>Select an identity user first.</Banner> : null}
          {advanced ? (
            <Card>
              <Sub>1. New keys</Sub>
              <Caption>Pick the keys on this device that should sign for {w.user?.name ?? 'the identity'} from now on. Create keys in Settings if needed.</Caption>
              {w.keys.map((k) => {
                const on = selectedKeys.includes(k.id);
                return (
                  <ListItem
                    key={k.id}
                    title={k.name}
                    subtitle={k.scheme}
                    selected={on}
                    onPress={() => setSelectedKeys((s) => (on ? s.filter((x) => x !== k.id) : [...s, k.id]))}
                    right={on ? <Pill label="new key" tone="primary" small /> : null}
                  />
                );
              })}
            </Card>
          ) : me && !recoveryKey ? (
            <Card>
              <Sub>A new recovery phrase</Sub>
              <Caption>The recovery gives the identity the key of new words: whoever caused the trouble may hold the old ones. Write the new words down first.</Caption>
              <Button title="Make a new recovery phrase" kind="secondary" small icon="key-outline" onPress={prepare} loading={preparing} />
            </Card>
          ) : null}
          {myKeyList && me && w.info ? (
            <Card>
              <Sub>{advanced ? '2. ' : ''}Send this to your guardians</Sub>
              <Caption>Each guardian scans it from their wallet, or pastes the link there or under Identity → Recovery → Help someone.</Caption>
              <LinkShare link={encodeLink({ request: 'recover', net: netOf(w.info.status.chainId), id: hex(me.id), keys: myKeyList })} />
              {newKeys ? (
                <Caption>
                  They should see {newKeys.length === 1 ? 'the key' : 'the keys'} {newKeys.map(keyFingerprint).join(', ')} to be installed.
                </Caption>
              ) : null}
            </Card>
          ) : null}
        </>
      ) : (
        <>
          <Card>
            <Sub>Who is recovering?</Sub>
            <Field
              label="Recovery link"
              value={linkText}
              onChangeText={setLinkText}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="ting:recover?…"
              multiline
              error={recoverLink && 'problem' in recoverLink ? sentence(recoverLink.problem) : null}
            />
            {targetId ? <Caption>Recovering {nameOf(w.names, targetId)}.</Caption> : <Caption>Paste the link they sent, or scan its code from the wallet.</Caption>}
            {newKeys && newKeys.length === 0 ? <Caption style={{ color: t.danger }}>The link lists no keys.</Caption> : null}
          </Card>
          {newKeys && newKeys.length > 0 ? (
            <Card>
              <Sub>Keys to install</Sub>
              <Caption>
                Approving hands the identity to {newKeys.length === 1 ? 'this key' : 'these keys'}. Check with the person that they match what their new device shows.
              </Caption>
              {newKeys.map((k, i) => (
                <ListItem key={i} title={keyFingerprint(k)} subtitle={keyLabel(k)} />
              ))}
            </Card>
          ) : null}
          {!w.identity ? <Banner tone="warning">Only an identity on chain can approve; switch to one of yours.</Banner> : null}
        </>
      )}

      {targetId && current && targetInfo === null && keyList ? <Banner tone="warning">That identity is not on chain.</Banner> : null}
      {targetInfo && status ? (
        <Card tone="alt">
          <Sub>Status</Sub>
          <Row style={{ flexWrap: 'wrap' }}>
            <Pill label={`${status.approvals} of ${status.required} approvals`} tone={status.approvals >= status.required ? 'success' : 'warning'} />
            {status.executableAt ? <Pill label={due ? 'ready' : `ready ${relativeTime(status.executableAt)}`} tone={due ? 'success' : 'accent'} /> : null}
            {status.standing === 'approved' ? <Pill label="you approved" tone="primary" /> : null}
            {status.standing === 'withdrawn' ? <Pill label="you withdrew" tone="neutral" /> : null}
          </Row>
          <Caption>
            {advanced ? `Proposal ${hex(status.proposal).slice(0, 16)}… in recovery epoch ${targetInfo.core.recoveryEpoch.toString()}. ` : ''}
            The recovery delay is {relativeDuration(targetInfo.core.recoveryDelay)}. Reaching the threshold voids every pending change of the identity; a change to
            its guardians, threshold or delays voids the approvals.
          </Caption>
          <FeeRow fee={fee} />
          {mode === 'help' && status.approvesOther ? (
            <>
              <Banner tone="warning">
                You approve another recovery of this identity. A guardian supports one recovery at a time: withdraw that approval to approve this one.
              </Banner>
              <Button title="Withdraw the other approval" kind="ghost" loading={busy === 'withdraw-other'} onPress={() => approval(false, status.approvesOther!)} disabled={!w.identity} />
            </>
          ) : null}
          {mode === 'help' ? (
            <Row>
              <Button
                title="Approve"
                loading={busy === 'approve'}
                onPress={() => approval(true)}
                disabled={!w.identity || !newKeys || newKeys.length === 0 || status.standing !== null || status.approvesOther !== null}
              />
              <Button title="Withdraw" kind="ghost" loading={busy === 'withdraw'} onPress={() => approval(false)} disabled={!w.identity || status.standing !== 'approved'} />
            </Row>
          ) : (
            <Button
              title={due ? 'Recover now' : 'Recover (not yet)'}
              disabled={!due}
              loading={busy === 'recover'}
              onPress={() =>
                act('recover', 'Recover identity', async () => recover(w.node, w.info!.status.chainId, w.info!.canonical.registry, targetId!, keyList!, await fee.resolve()))
              }
            />
          )}
        </Card>
      ) : null}
      {mode === 'mine' ? (
        <Caption>Once enough guardians approve and the recovery delay passes, press Recover; the old devices then stop working.</Caption>
      ) : null}
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
