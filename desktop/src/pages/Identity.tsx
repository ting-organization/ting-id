import { useRef, useState } from 'react';
import { describeOperation, nameOf, OTHER_CHAIN_USER, relativeDuration, sentence } from '@/core/describe';
import { equal, hex, unhex } from '@/core/chain/bytes';
import { schemeOf } from '@/core/chain/contracts';
import { nowSeconds, relativeTime, shortId } from '@/core/chain/format';
import * as idp from '@/core/chain/identity';
import { SCHEME_LABEL, deviceCode } from '@/core/chain/keys';
import { slotLabel } from '@/core/state/chain';
import { useFee } from '@/core/state/fees';
import { deviceKeyOf, ensureDeviceKey } from '@/core/state/keys';
import { applyChange, cancelChanges, identitySigner, linkedDeviceKeys, useLinkedGuardians, userIdentity } from '@/core/state/registry';
import { useStore } from '@/core/state/store';
import { useWorkspace } from '@/core/state/workspace';
import { Avatar, Banner, Body, Button, Caption, Card, IconCircle, IdChip, ListItem, Page, Panel, Pill, Row, StatTile, Sub } from '@/ui/components';
import { FeeRow } from '@/ui/FeeRow';
import { useConfirm } from '@/ui/confirm';
import { useModal } from '@/ui/modal';

export function IdentityPage() {
  const modal = useModal();
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
      modal.open(`/tx/${txId}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      running.current = false;
      setBusy(null);
    }
  };

  const propose = (params: Record<string, string>) => modal.open(`/identity/propose?${new URLSearchParams(params)}`);

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
      <Page title="Identity" narrow>
        <Banner>Create a user first.</Banner>
        <div>
          <Button title="Get started" icon="person-add-outline" onClick={() => modal.open('/user/new')} />
        </div>
      </Page>
    );
  }
  if (w.user.kind === 'key') {
    return (
      <Page title="Identity" narrow>
        <Card>
          <Row>
            <Avatar name={w.user.name} color={w.user.color} size={48} />
            <span className="stack grow" style={{ gap: 2 }}>
              <span className="heading">{w.user.name}</span>
              <Caption>Simple account</Caption>
            </span>
          </Row>
          <Body muted>One account on this computer, without guardians or recovery. An identity works from several devices, lets guardians restore access, and adds a time-locked savings vault.</Body>
          <div>
            <Button title="Create an identity" icon="finger-print-outline" onClick={() => modal.open('/user/new')} />
          </div>
        </Card>
      </Page>
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

  return (
    <Page title="Identity">
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Card>
        <div className="identity-summary">
          <Row gap={16} className="grow">
            <Avatar name={w.user.name} color={w.user.color} size={40} />
            <span className="stack" style={{ gap: 6 }}>
              <Row gap={10}>
                <span className="heading">{w.user.name}</span>
                <Pill label={id ? 'on chain' : 'pending creation'} tone={id ? 'success' : 'warning'} />
              </Row>
              <span>
                <IdChip label="ID" value={w.user.identityId!} />
              </span>
            </span>
          </Row>
          {id ? (
            <div className="stats">
              <StatTile
                icon={advanced ? 'key-outline' : 'phone-portrait-outline'}
                value={keyCount.toString()}
                label={advanced ? (keyCount === 1 ? 'Key' : 'Keys') : keyCount === 1 ? 'Device' : 'Devices'}
                hint={linked.length > 0 ? 'including this computer' : 'not this computer yet'}
              />
              <StatTile
                icon="shield-checkmark-outline"
                tone="accent"
                value={guardianCount.toString()}
                label={guardianCount === 1 ? 'Guardian' : 'Guardians'}
                hint={guardianCount > 0 ? `${idp.required(id.core)} needed to recover` : 'add someone you trust'}
              />
            </div>
          ) : (
            <Caption>{w.otherChain ? OTHER_CHAIN_USER : 'Waiting for the next block.'}</Caption>
          )}
        </div>
      </Card>

      {id && linked.length === 0 ? (
        <Card tone="alt">
          <Row>
            <IconCircle name="desktop-outline" tone="warning" />
            <span className="stack grow" style={{ gap: 2 }}>
              <Sub>This computer cannot act for {w.user.name} yet</Sub>
              <Caption>
                {pendingKey
                  ? 'Another device has proposed to add it; it works once that change applies.'
                  : 'Add it from a device that already has access, or ask your guardians to recover the identity.'}
              </Caption>
            </span>
            <Button title="Recover with guardians" kind="ghost" small icon="refresh-outline" onClick={() => modal.open('/identity/recovery?mode=mine')} />
          </Row>
          {deviceKey ? (
            <>
              <Caption>Add this code on a device that has access, under Devices → Add device:</Caption>
              <IdChip label="code" value={deviceCode(deviceKey.scheme, unhex(deviceKey.pub))} full />
            </>
          ) : (
            <div>
              <Button
                title="Prepare this computer"
                kind="secondary"
                small
                icon="key-outline"
                onClick={() => ensureDeviceKey(w.user!, w.keys).catch((e) => setError((e as Error).message))}
              />
            </div>
          )}
        </Card>
      ) : null}

      {id ? (
        <>
          <div className="grid-2">
            <div className="col">
              <Panel title={advanced ? 'Keys' : 'Devices'} action={advanced ? 'Add key' : 'Add device'} onAction={() => propose({ kind: 'addKey' })}>
                {id.keys.map(({ keyId, key }) => {
                  const kind = w.info ? schemeOf(w.info.canonical, key.policy) : null;
                  const device = w.keys.find((k) => equal(unhex(k.pub), key.params));
                  const label = kind === 'identity' ? 'another identity' : kind === 'multisig' ? 'multisig' : kind ? SCHEME_LABEL[kind] : 'custom policy';
                  const title = advanced ? (device ? device.name : `Key ${keyId}`) : device ? 'This computer' : `Device ${keyId + 1n}`;
                  const subtitle = advanced ? `key ${keyId} · ${label} · ${shortId(key.params, 10, 6)}` : 'can act for this identity';
                  return (
                    <ListItem
                      key={keyId.toString()}
                      left={<IconCircle name={device ? 'desktop-outline' : advanced ? 'key-outline' : 'phone-portrait-outline'} />}
                      title={title}
                      subtitle={subtitle}
                      right={
                        <Row>
                          {device && advanced ? <Pill label="this computer" tone="primary" small /> : null}
                          {id.keys.length > 1 ? <Button title="Remove" kind="ghost" small onClick={() => propose({ kind: 'removeKey', keyId: keyId.toString() })} /> : null}
                        </Row>
                      }
                    />
                  );
                })}
              </Panel>
            </div>
            <div className="col">
              <Panel
                title="Guardians"
                action="Link"
                onAction={() => propose({ kind: 'addGuardian' })}
                info={`People you trust. ${idp.required(id.core)} of them can hand the identity to a new device if yours is lost; they can never move your funds.`}
              >
                {guardians.length === 0 ? (
                  <div style={{ padding: '14px 16px' }}>
                    <Caption>{id.core.guardianCount > 0n ? `${id.core.guardianCount} linked, none known to this computer.` : 'No guardians linked yet.'}</Caption>
                  </div>
                ) : null}
                {guardians.map((g) => (
                  <ListItem
                    key={hex(g)}
                    left={<IconCircle name="shield-checkmark-outline" tone="accent" />}
                    title={nameOf(w.names, g)}
                    subtitle={shortId(g, 10, 6)}
                    right={<Button title="Unlink" kind="ghost" small onClick={() => propose({ kind: 'removeGuardian', id: hex(g) })} />}
                  />
                ))}
              </Panel>
            </div>
            <div className="col">
              <Panel
                title="Accounts"
                info={
                  vault
                    ? `Spending is instant. Withdrawals from ${slotLabel(vault.slot)} wait ${relativeDuration(vault.delay)}, and any of your devices can cancel one meanwhile, so a stolen device cannot empty it.`
                    : undefined
                }
              >
                {id.slots.map((s) => (
                  <ListItem
                    key={s.slot}
                    left={<IconCircle name={s.delay ? 'lock-closed-outline' : 'wallet-outline'} tone={s.delay ? 'accent' : 'primary'} />}
                    title={slotLabel(s.slot)}
                    subtitle={s.delay ? 'time-locked' : 'instant'}
                  />
                ))}
              </Panel>
            </div>
            <div className="col">
              <Panel
                title="Recovery"
                info="Lost a device? On the new one, add this identity (Add a user → Existing identity) and start a recovery there: it shows a code for your guardians. Once enough approve and the recovery delay passes, the new device takes over and the old ones stop working."
                padded
              >
                <Row wrap>
                  <Button title="Recover this identity" kind="secondary" small icon="refresh-outline" onClick={() => modal.open('/identity/recovery?mode=mine')} />
                  <Button title="Help someone" kind="ghost" small icon="hand-left-outline" onClick={() => modal.open('/identity/recovery?mode=help')} />
                </Row>
              </Panel>
            </div>
          </div>

          {id.pending.length > 0 ? (
            <Panel
              title="Pending changes"
              count={id.pending.length}
              attention
              info={`A change waits ${relativeDuration(id.core.changeDelay)}, and any of your devices can cancel it meanwhile. It is void once the device that proposed it is removed, or when guardians approve a recovery.`}
              actions={
                linked.length > 0 && id.pending.length > 1 ? (
                  <Button title="Cancel all" kind="danger" small loading={busy === 'cancel-all'} onClick={() => busy === null && confirmCancel(id.pending.map((p) => p.changeNo), 'cancel-all')} />
                ) : null
              }
              footer={
                <div style={{ flex: 1 }}>
                  <FeeRow fee={fee} />
                </div>
              }>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Change</th>
                      <th>No.</th>
                      <th>Applies</th>
                      <th aria-label="Actions"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {id.pending.map(({ changeNo, record }) => {
                      const due = Number(record.effectiveAt) <= nowSeconds();
                      return (
                        <tr key={changeNo.toString()}>
                          <td>{record.operations.map((o) => describeOperation(o, w.names, w.advanced)).join('; ')}</td>
                          <td className="num soft">#{changeNo.toString()}</td>
                          <td>{due ? 'ready to apply' : relativeTime(record.effectiveAt)}</td>
                          <td className="r">
                            <Row gap={4} style={{ justifyContent: 'flex-end' }}>
                              {due ? <Button title="Apply" kind="secondary" small plain loading={busy === `apply-${changeNo}`} onClick={() => apply(changeNo)} /> : null}
                              {linked.length > 0 ? (
                                <Button title="Cancel" kind="danger" small plain loading={busy === `cancel-${changeNo}`} onClick={() => confirmCancel([changeNo], `cancel-${changeNo}`)} />
                              ) : null}
                            </Row>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </Panel>
          ) : null}

          {advanced ? (
            <>
              <Panel
                title="Time locks"
                action="Add vault"
                onAction={() => propose({ kind: 'setSlot', slot: id.core.slotCount.toString() })}
                info="Recovery must be shorter than changes, so guardians can undo what a thief starts. A change here applies after the current change delay; lowering an account's lock also waits out that lock."
              >
                <ListItem
                  left={<IconCircle name="hourglass-outline" />}
                  title="Changes and recovery"
                  subtitle={`changes apply after ${relativeDuration(id.core.changeDelay)} · guardians recover after ${relativeDuration(id.core.recoveryDelay)}`}
                  right={<Button title="Change" kind="ghost" small onClick={() => propose({ kind: 'setDelays' })} />}
                />
                <ListItem
                  left={<IconCircle name="people-outline" tone="accent" />}
                  title="Recovery threshold"
                  subtitle={`${idp.required(id.core)} guardian approvals${id.core.threshold === 0n ? ', more than half' : ''}`}
                  right={<Button title="Change" kind="ghost" small onClick={() => propose({ kind: 'setThreshold' })} />}
                />
                {id.slots.map((s) => (
                  <ListItem
                    key={s.slot}
                    left={<IconCircle name={s.delay ? 'lock-closed-outline' : 'wallet-outline'} tone={s.delay ? 'accent' : 'primary'} />}
                    title={slotLabel(s.slot)}
                    subtitle={s.delay ? `withdrawals wait ${relativeDuration(s.delay)}` : 'instant'}
                    right={<Button title="Change" kind="ghost" small onClick={() => propose({ kind: 'setSlot', slot: String(s.slot) })} />}
                  />
                ))}
              </Panel>
            </>
          ) : null}
        </>
      ) : null}
    </Page>
  );
}
