import React, { useRef, useState } from 'react';
import { describeOperation, nameOf, OTHER_CHAIN_USER, relativeDuration, sentence } from '@/ui/describe';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useRouter } from 'expo-router';
import { equal, hex, unhex } from '@/chain/bytes';
import { schemeOf } from '@/chain/contracts';
import { nowSeconds, relativeTime, shortId } from '@/chain/format';
import * as idp from '@/chain/identity';
import { SCHEME_LABEL, deviceCode } from '@/chain/keys';
import { slotLabel } from '@/state/chain';
import { useFee } from '@/state/fees';
import { deviceKeyOf, ensureDeviceKey } from '@/state/keys';
import { applyChange, cancelChanges, identitySigner, linkedDeviceKeys, useLinkedGuardians, userIdentity } from '@/state/registry';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import {
  Avatar,
  Banner,
  Body,
  Button,
  Caption,
  Card,
  IconCircle,
  IdChip,
  Info,
  InkHeader,
  ListItem,
  Pill,
  Row,
  Screen,
  SectionHeader,
  StatTile,
  Sub,
} from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { FeeRow } from '@/ui/FeeRow';
import { ProfileButton } from '@/ui/Profile';
import { font, spacing, type, useTheme } from '@/ui/theme';

export default function IdentityScreen() {
  const t = useTheme();
  const router = useRouter();
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const addSubmitted = useStore((s) => s.addSubmitted);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const me = w.user ? userIdentity(w.user) : null;
  // Who may be a guardian, as this device knows: the ones named at creation and the other identities here.
  const known = new Map<string, Uint8Array>();
  const creation = w.user?.creation ? idp.decodeCreation(unhex(w.user.creation)) : null;
  for (const g of creation?.guardians ?? []) known.set(hex(g), g);
  for (const u of users) {
    const other = userIdentity(u);
    if (me && other && !equal(other.id, me.id)) known.set(hex(other.id), other.id);
  }
  const guardians = useLinkedGuardians(me && w.identity ? me : null, [...known.values()]);
  // One fee for cancelling and applying pending changes; registry operations pay nothing themselves.
  const fee = useFee(w, null);

  // One action at a time: a second button pressed meanwhile does nothing.
  const running = useRef(false);
  const act = async (label: string, summary: string, f: () => Promise<string>) => {
    if (running.current) return;
    running.current = true;
    setBusy(label);
    setError(null);
    try {
      const txId = await f();
      if (w.user) addSubmitted({ id: txId, at: nowSeconds(), summary, userId: w.user.id });
      router.push({ pathname: '/tx/[id]', params: { id: txId } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      running.current = false;
      setBusy(null);
    }
  };

  const propose = (params: Record<string, string>) => router.push({ pathname: '/identity/propose', params });

  // One signature cancels any number of pending changes.
  const cancel = (label: string, changeNos: bigint[]) =>
    act(label, changeNos.length === 1 ? `Cancel change #${changeNos[0]}` : `Cancel ${changeNos.length} changes`, async () => {
      if (!w.user || !w.info || !w.identity) throw new Error('not ready');
      const signer = await identitySigner(w.user, w.info.canonical, w.keys, w.identity);
      return cancelChanges(w.node, w.info.status.chainId, w.identity, signer, changeNos, await fee.resolve());
    });
  const confirm = useConfirm();
  const confirmCancel = async (changeNos: bigint[], label: string) => {
    const one = changeNos.length === 1 ? w.identity?.pending.find((p) => p.changeNo === changeNos[0]) : undefined;
    const yes = await confirm({
      title: changeNos.length === 1 ? 'Cancel this change?' : `Cancel all ${changeNos.length} changes?`,
      body: one ? `${one.record.operations.map((o) => describeOperation(o, w.names, w.advanced)).join('; ')}. It will not apply.` : 'None of them will apply.',
      confirm: changeNos.length === 1 ? 'Cancel change' : 'Cancel all',
      back: changeNos.length === 1 ? 'Keep it' : 'Keep them',
      danger: true,
    });
    if (yes) cancel(label, changeNos);
  };
  const apply = (changeNo: bigint) =>
    act(`apply-${changeNo}`, `Apply change #${changeNo}`, async () => {
      if (!w.info || !w.identity) throw new Error('not ready');
      return applyChange(w.node, w.info.status.chainId, w.identity, changeNo, await fee.resolve());
    });

  if (!w.user) {
    return (
      <Screen title="Identity">
        <Banner>Create a user first.</Banner>
        <Button title="Get started" icon="person-add-outline" onPress={() => router.push('/user/new')} />
      </Screen>
    );
  }
  if (w.user.kind === 'key') {
    return (
      <Screen title="Identity" right={<ProfileButton />}>
        <Card>
          <Row>
            <Avatar name={w.user.name} color={w.user.color} size={48} />
            <View style={{ flex: 1 }}>
              <Text style={[type.heading, { color: t.text }]}>{w.user.name}</Text>
              <Caption>Simple account</Caption>
            </View>
          </Row>
          <Body muted>One account on this device, without guardians. An identity works from several devices, lets guardians restore access, and adds a time-locked savings vault.</Body>
          <Button title="Create an identity" icon="finger-print-outline" onPress={() => router.push('/user/new')} />
        </Card>
      </Screen>
    );
  }

  const id = w.identity;
  const vault = id?.slots.find((s) => s.delay > 0);
  const advanced = w.advanced;
  const linked = id && w.info ? linkedDeviceKeys(w.info.canonical, id, w.keys) : [];
  const deviceKey = deviceKeyOf(w.user, w.keys) ?? linked[0]?.key ?? null;
  const pendingKey = deviceKey && id ? id.pending.some((p) => p.record.operations.some((o) => o.kind === 'addKey' && equal(o.key.params, unhex(deviceKey.pub)))) : false;
  const keyCount = id ? Number(id.core.keyCount) : 0;
  const guardianCount = id ? Number(id.core.guardianCount) : 0;

  const header = (
    <InkHeader>
      <Row style={{ justifyContent: 'space-between' }}>
        <Text style={{ color: t.onHeaderMuted, fontSize: 14, ...font.bold }}>Identity</Text>
        <ProfileButton size={34} />
      </Row>
      <Row gap={14}>
        <Avatar name={w.user.name} color={w.user.color} size={52} />
        <View style={{ flex: 1, gap: 6 }}>
          <Text style={{ color: t.onHeader, fontSize: 24, letterSpacing: -0.5, ...font.extrabold }} numberOfLines={1}>
            {w.user.name}
          </Text>
          <Row gap={6}>
            <Ionicons name={id ? 'shield-checkmark' : 'hourglass-outline'} size={14} color={id ? '#34D399' : '#FCD34D'} />
            <Text style={{ color: t.onHeaderMuted, fontSize: 12.5, ...font.semibold }}>{id ? 'On chain' : w.otherChain ? 'Another network' : 'Waiting for the next block'}</Text>
          </Row>
        </View>
      </Row>
      <IdChip label="ID" value={w.user.identityId!} light />
      {id ? (
        <Row style={{ alignItems: 'stretch' }}>
          <StatTile
            onHeader
            icon={advanced ? 'key-outline' : 'phone-portrait-outline'}
            value={keyCount.toString()}
            label={advanced ? (keyCount === 1 ? 'Key' : 'Keys') : keyCount === 1 ? 'Device' : 'Devices'}
            hint={linked.length > 0 ? 'including this device' : 'not this device yet'}
          />
          <StatTile
            onHeader
            icon="shield-checkmark-outline"
            value={guardianCount.toString()}
            label={guardianCount === 1 ? 'Guardian' : 'Guardians'}
            hint={guardianCount > 0 ? `${idp.required(id.core)} needed to recover` : 'add someone you trust'}
          />
        </Row>
      ) : null}
    </InkHeader>
  );

  return (
    <Screen header={header}>
      {w.otherChain ? <Banner tone="warning">{OTHER_CHAIN_USER}</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}

      {id && linked.length === 0 ? (
        <Card tone="warning">
          <Row>
            <IconCircle name="phone-portrait-outline" color={t.warning} bg={t.warningSoft} />
            <View style={{ flex: 1 }}>
              <Sub>This device cannot act for {w.user.name} yet</Sub>
              <Caption>
                {pendingKey
                  ? 'Another device has proposed to add it; it works once that change applies.'
                  : 'Add it from a device that already has access, or ask your guardians to recover the identity.'}
              </Caption>
            </View>
          </Row>
          {deviceKey ? (
            <>
              <Caption>Add this code on a device that has access, under Devices → Add device:</Caption>
              <IdChip label="code" value={deviceCode(deviceKey.scheme, unhex(deviceKey.pub))} full />
            </>
          ) : (
            <Button
              title="Prepare this device"
              kind="secondary"
              small
              icon="key-outline"
              onPress={() => ensureDeviceKey(w.user!, w.keys).catch((e) => setError((e as Error).message))}
            />
          )}
          <Button
            title="Recover with guardians"
            kind="ghost"
            small
            icon="refresh-outline"
            onPress={() => router.push({ pathname: '/identity/recovery', params: { mode: 'mine' } })}
          />
        </Card>
      ) : null}

      {id ? (
        <>
          <SectionHeader title={advanced ? 'Keys' : 'Devices'} action={advanced ? 'Add key' : 'Add device'} onAction={() => propose({ kind: 'addKey' })} />
          <Card padded={false} style={{ gap: 0 }}>
            {id.keys.map(({ keyId, key }, i) => {
              const kind = w.info ? schemeOf(w.info.canonical, key.policy) : null;
              const device = w.keys.find((k) => equal(unhex(k.pub), key.params));
              const label = kind === 'identity' ? 'another identity' : kind === 'multisig' ? 'multisig' : kind ? SCHEME_LABEL[kind] : 'custom policy';
              const title = advanced ? (device ? device.name : `Key ${keyId}`) : device ? 'This device' : `Device ${keyId + 1n}`;
              const subtitle = advanced ? `key ${keyId} · ${label} · ${shortId(key.params, 10, 6)}` : 'can act for this identity';
              return (
                <View key={keyId.toString()} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                  <ListItem
                    left={<IconCircle name={device ? 'phone-portrait-outline' : advanced ? 'key-outline' : 'tablet-portrait-outline'} color={t.primary} bg={t.primarySoft} />}
                    title={title}
                    subtitle={subtitle}
                    right={
                      <Row>
                        {device && advanced ? <Pill label="this device" tone="primary" small /> : null}
                        {id.keys.length > 1 ? <Button title="Remove" kind="ghost" small onPress={() => propose({ kind: 'removeKey', keyId: keyId.toString() })} /> : null}
                      </Row>
                    }
                  />
                </View>
              );
            })}
          </Card>

          <SectionHeader
            title="Guardians"
            action="Link"
            onAction={() => propose({ kind: 'addGuardian' })}
            info={`People you trust. ${idp.required(id.core)} of them can hand the identity to a new device if yours is lost; they can never move your funds.`}
          />
          <Card padded={false} style={{ gap: 0 }}>
            {guardians.length === 0 ? (
              <View style={{ padding: spacing.lg }}>
                <Caption>{id.core.guardianCount > 0n ? `${id.core.guardianCount} linked, none known to this device.` : 'No guardians linked yet.'}</Caption>
              </View>
            ) : null}
            {guardians.map((g, i) => (
              <View key={hex(g)} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                <ListItem
                  left={<IconCircle name="shield-checkmark-outline" color={t.accent} bg={t.accentSoft} />}
                  title={nameOf(w.names, g)}
                  subtitle={shortId(g, 10, 6)}
                  right={<Button title="Unlink" kind="ghost" small onPress={() => propose({ kind: 'removeGuardian', id: hex(g) })} />}
                />
              </View>
            ))}
          </Card>

          <SectionHeader
            title="Accounts"
            info={
              vault
                ? `Spending is instant. Withdrawals from ${slotLabel(vault.slot)} wait ${relativeDuration(vault.delay)}, and any of your devices can cancel one meanwhile, so a stolen phone cannot empty it.`
                : undefined
            }
          />
          <Card padded={false} style={{ gap: 0 }}>
            {id.slots.map((s, i) => (
              <View key={s.slot} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                <ListItem
                  left={<IconCircle name={s.delay ? 'lock-closed-outline' : 'wallet-outline'} color={s.delay ? t.accent : t.primary} bg={s.delay ? t.accentSoft : t.primarySoft} />}
                  title={slotLabel(s.slot)}
                  subtitle={s.delay ? 'time-locked' : 'instant'}
                />
              </View>
            ))}
          </Card>

          {id.pending.length > 0 ? (
            <Card tone="warning" padded={false} style={{ gap: 0 }}>
              <Row style={{ paddingHorizontal: spacing.lg, paddingTop: 14, paddingBottom: 4 }}>
                <Ionicons name="hourglass-outline" size={16} color={t.warning} />
                <Text style={{ color: t.warning, fontSize: 13.5, ...font.extrabold }}>
                  {id.pending.length} pending {id.pending.length === 1 ? 'change' : 'changes'}
                </Text>
                <Info
                  title="Pending changes"
                  text={`A change waits ${relativeDuration(id.core.changeDelay)}, and any of your devices can cancel it meanwhile. It is void once the device that proposed it is removed, or when guardians approve a recovery.`}
                />
                <View style={{ flex: 1 }} />
                {linked.length > 0 && id.pending.length > 1 ? (
                  <Pressable onPress={() => busy === null && confirmCancel(id.pending.map((p) => p.changeNo), 'cancel-all')} hitSlop={8}>
                    <Text style={{ color: t.danger, fontSize: 13.5, ...font.bold }}>{busy === 'cancel-all' ? 'Cancelling…' : 'Cancel all'}</Text>
                  </Pressable>
                ) : null}
              </Row>
              {id.pending.map(({ changeNo, record }, i) => {
                const due = Number(record.effectiveAt) <= nowSeconds();
                return (
                  <View key={changeNo.toString()} style={{ gap: 10, paddingHorizontal: spacing.lg, paddingVertical: 12, borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.warningLine }}>
                    <Row gap={12}>
                      <IconCircle name={due ? 'checkmark-circle-outline' : 'time-outline'} color={t.warning} bg={t.warningLine} size={38} />
                      <View style={{ flex: 1, gap: 1 }}>
                        <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }}>{record.operations.map((o) => describeOperation(o, w.names, w.advanced)).join('; ')}</Text>
                        <Caption style={{ color: t.warning }}>
                          #{changeNo.toString()} · {due ? 'ready to apply' : `applies ${relativeTime(record.effectiveAt)}`}
                        </Caption>
                      </View>
                      {linked.length > 0 ? (
                        <Button title="Cancel" kind="danger" small loading={busy === `cancel-${changeNo}`} onPress={() => confirmCancel([changeNo], `cancel-${changeNo}`)} />
                      ) : null}
                    </Row>
                    {due ? (
                      <Row style={{ paddingLeft: 50 }}>
                        <Button title="Apply" kind="secondary" small icon="checkmark" loading={busy === `apply-${changeNo}`} onPress={() => apply(changeNo)} />
                      </Row>
                    ) : null}
                  </View>
                );
              })}
              <View style={{ borderTopWidth: 1, borderTopColor: t.warningLine, paddingHorizontal: spacing.lg, paddingVertical: 8 }}>
                <FeeRow fee={fee} />
              </View>
            </Card>
          ) : null}

          <SectionHeader
            title="Recovery"
            icon="shield-outline"
            info="Lost your phone? On the new one, restore with your recovery phrase. Without it, add the identity by its ID (Add a user → Restore → Identity ID) and start a recovery there: it shows a code for your guardians. Once enough approve and the recovery delay passes, the new phone takes over with a new recovery phrase, and the old devices and words stop working."
          />
          <Card>
            <Row style={{ flexWrap: 'wrap' }}>
              <Button title="Recover this identity" kind="secondary" small icon="refresh-outline" onPress={() => router.push({ pathname: '/identity/recovery', params: { mode: 'mine' } })} />
              <Button title="Help someone" kind="ghost" small icon="hand-left-outline" onPress={() => router.push({ pathname: '/identity/recovery', params: { mode: 'help' } })} />
            </Row>
          </Card>

          {advanced ? (
            <>
              <SectionHeader
                title="Time locks"
                icon="hourglass-outline"
                action="Add vault"
                onAction={() => propose({ kind: 'setSlot', slot: id.core.slotCount.toString() })}
                info="Recovery must be shorter than changes, so guardians can undo what a thief starts. A change here applies after the current change delay; lowering an account's lock also waits out that lock."
              />
              <Card padded={false} style={{ gap: 0 }}>
                <ListItem
                  left={<IconCircle name="hourglass-outline" color={t.primary} bg={t.primarySoft} />}
                  title="Changes and recovery"
                  subtitle={`changes ${relativeDuration(id.core.changeDelay)} · recovery ${relativeDuration(id.core.recoveryDelay)}`}
                  right={<Button title="Change" kind="ghost" small onPress={() => propose({ kind: 'setDelays' })} />}
                />
                <View style={{ borderTopWidth: 1, borderTopColor: t.border }}>
                  <ListItem
                    left={<IconCircle name="people-outline" color={t.accent} bg={t.accentSoft} />}
                    title="Recovery threshold"
                    subtitle={`${idp.required(id.core)} approvals${id.core.threshold === 0n ? ', more than half' : ''}`}
                    right={<Button title="Change" kind="ghost" small onPress={() => propose({ kind: 'setThreshold' })} />}
                  />
                </View>
                {id.slots.map((s) => (
                  <View key={s.slot} style={{ borderTopWidth: 1, borderTopColor: t.border }}>
                    <ListItem
                      left={<IconCircle name={s.delay ? 'lock-closed-outline' : 'wallet-outline'} color={s.delay ? t.accent : t.primary} bg={s.delay ? t.accentSoft : t.primarySoft} />}
                      title={slotLabel(s.slot)}
                      subtitle={s.delay ? `withdrawals wait ${relativeDuration(s.delay)}` : 'instant'}
                      right={<Button title="Change" kind="ghost" small onPress={() => propose({ kind: 'setSlot', slot: String(s.slot) })} />}
                    />
                  </View>
                ))}
              </Card>
            </>
          ) : null}
        </>
      ) : null}
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
