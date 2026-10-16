import { useEffect, useState } from 'react';
import { hex, isHex } from '@/core/chain/bytes';
import { contractCode } from '@/core/chain/contracts';
import { formatAmount, relativeTime, shortId } from '@/core/chain/format';
import { SCHEMES, SCHEME_HINT, SCHEME_LABEL, Scheme } from '@/core/chain/keys';
import { canonicalId } from '@/core/chain/tx';
import { knownCoinsFor } from '@/core/config/known-coins';
import { fetchCoin, isStandardCoin, useDiscoveredCoins } from '@/core/state/coins';
import { DEFAULT_SCHEME, createDeviceKey, deleteEverything } from '@/core/state/keys';
import { deleteSecret, KEYSTORE_NOTE, uuid } from '@/core/state/keystore';
import { keyUsers } from '@/core/state/registry';
import { useIdentities } from '@/core/state/chain';
import { nextColor, useStore } from '@/core/state/store';
import type { KeyMeta } from '@/core/state/types';
import { useWorkspace } from '@/core/state/workspace';
import { sentence, siteName } from '@/core/describe';
import { CoinBadge } from '@/ui/CoinPicker';
import { FeeSettings } from '@/ui/FeeSettings';
import { NodeSettings } from '@/ui/NodeSettings';
import { Banner, Button, Caption, Dialog, Field, IconCircle, IdChip, ListItem, Page, Panel, Pill, Row, Segmented, Sub, Switch } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { Icon } from '@/ui/icons';

const STANDARD: { name: Parameters<typeof contractCode>[0]; label: string }[] = [
  { name: 'policy-secp256k1', label: 'secp256k1 policy' },
  { name: 'policy-p256', label: 'P-256 policy' },
  { name: 'policy-multisig-secp256k1', label: 'Multisig policy' },
  { name: 'id-registry', label: 'Identity registry' },
];

export function SettingsPage() {
  const w = useWorkspace();
  const settings = useStore((s) => s.settings);
  const setSettings = useStore((s) => s.setSettings);
  const addCoin = useStore((s) => s.addCoin);
  const removeCoin = useStore((s) => s.removeCoin);
  const keys = useStore((s) => s.keys);
  const users = useStore((s) => s.users);
  const removeKey = useStore((s) => s.removeKey);
  const addUser = useStore((s) => s.addUser);
  const identities = useIdentities(users);
  const [wipeError, setWipeError] = useState<string | null>(null);
  const allSignatures = useStore((s) => s.signatures);
  const clearSignatures = useStore((s) => s.clearSignatures);
  const signatures = allSignatures.filter((n) => n.userId === w.user?.id);
  const [coinInput, setCoinInput] = useState('');
  const [coinError, setCoinError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [deployed, setDeployed] = useState<Record<string, boolean>>({});
  const [keyOpen, setKeyOpen] = useState(false);
  const [keyName, setKeyName] = useState('');
  const [scheme, setScheme] = useState<Scheme>(DEFAULT_SCHEME);
  const [keyError, setKeyError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [keyProblem, setKeyProblem] = useState<string | null>(null);
  const confirm = useConfirm();
  const chainHex = w.info ? hex(w.info.status.chainId) : null;
  const known = chainHex ? knownCoinsFor(chainHex).filter((k) => !settings.coins.includes(k.contract)) : [];
  const found = useDiscoveredCoins(w.info?.status.height, w.scope, w.accounts, [...settings.coins, ...(w.info ? [hex(w.info.canonical.registry)] : [])]);
  const missingContracts = STANDARD.filter((s) => deployed[s.name] === false);

  useEffect(() => {
    let alive = true;
    if (!w.info) return;
    const chain = w.info.status.chainId;
    Promise.all(STANDARD.map(async (s) => [s.name, await w.node.contractExists(canonicalId(chain, contractCode(s.name)))] as const))
      .then((entries) => alive && setDeployed(Object.fromEntries(entries)))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [w.node, chainHex, w.info?.status.height]); // eslint-disable-line react-hooks/exhaustive-deps

  const add = async (id: string) => {
    setAdding(true);
    setCoinError(null);
    try {
      const h = id.trim().toLowerCase();
      if (!isHex(h, 32)) throw new Error('a contract ID is 64 hex digits');
      if (!(await isStandardCoin(w.node, h))) throw new Error('that contract does not run the standard coin');
      if (!(await fetchCoin(w.node, h))) throw new Error('that coin is not initialized on this chain');
      addCoin(h);
      setCoinInput('');
    } catch (e) {
      setCoinError((e as Error).message);
    } finally {
      setAdding(false);
    }
  };

  // Who a key belongs to: the user it was made for, or an identity it signs for.
  const usedBy = (k: KeyMeta) => keyUsers(k, users, identities, w.info?.canonical ?? null);

  return (
    <Page title="Settings" narrow>
      <Panel title="Coins" info="Follow any coin that runs the standard coin contract by its contract id. The wallet verifies the contract's code before adding it.">
        {w.coins.length === 0 && w.missingCoins.length === 0 ? (
          <div style={{ padding: 16 }}>
            <Caption>No coins followed yet.</Caption>
          </div>
        ) : null}
        {w.coins.map((c) => (
          <ListItem
            key={c.hex}
            left={<CoinBadge coin={c} size={28} />}
            title={c.config.name}
            subtitle={
              <Row gap={6}>
                <Caption>{c.config.symbol}</Caption>
                <IdChip value={c.hex} />
              </Row>
            }
            right={
              <button
                className="link-button"
                title="Unfollow"
                aria-label={`Unfollow ${c.config.name}`}
                onClick={async () => {
                  if (await confirm({ title: `Unfollow ${c.config.name}?`, body: 'It disappears from this wallet. What you hold stays on chain, and you can follow it again from Coins.', confirm: 'Unfollow', back: 'Keep following', danger: true }))
                    removeCoin(c.hex);
                }}>
                <Icon name="trash-outline" size={18} color="var(--muted)" />
              </button>
            }
          />
        ))}
        {w.missingCoins.map((h) => (
          <ListItem key={h} title={shortId(h, 10, 6)} subtitle="not on this chain" right={<Button title="Remove" kind="ghost" small onClick={() => removeCoin(h)} />} />
        ))}
        <div className="stack" style={{ padding: 16, borderTop: '1px solid var(--border)' }}>
          <Field
            label="Add a coin by contract ID"
            value={coinInput}
            onChangeText={setCoinInput}
            placeholder="64 hex digits"
            error={coinError}
            mono
          />
          <div>
            <Button title="Add" kind="secondary" small icon="add" loading={adding} disabled={!isHex(coinInput, 32)} onClick={() => add(coinInput)} />
          </div>
        </div>
      </Panel>
      {known.length > 0 || found.length > 0 ? (
        <>
          <Panel title="Suggested">
            {known.map((k) => (
              <ListItem key={k.contract} title={k.name} subtitle="known on this chain" right={<Button title="Follow" kind="secondary" small onClick={() => add(k.contract)} />} />
            ))}
            {found.map((f) => (
              <ListItem
                key={f.info.hex}
                left={<CoinBadge coin={f.info} size={28} />}
                title={f.info.config.name}
                subtitle={`you hold ${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}`}
                right={<Button title="Follow" kind="secondary" small onClick={() => addCoin(f.info.hex)} />}
              />
            ))}
          </Panel>
        </>
      ) : null}

      <FeeSettings w={w} />

      <NodeSettings w={w} missing={missingContracts.map((c) => c.label)} />

      <Panel
        title="Signed for sites"
        info="Requests of sites this user answered on this computer: sign-ins, permissions and statements. Scan a site's code from the wallet to sign in with Ting ID."
        action={signatures.length > 0 ? 'Clear' : undefined}
        onAction={clearSignatures}
      >
        {signatures.length === 0 ? (
          <div style={{ padding: '14px 16px' }}>
            <Caption>Nothing signed for a site yet.</Caption>
          </div>
        ) : null}
        {signatures.slice(0, 30).map((n, i) => (
          <ListItem
            key={`${n.at}-${i}`}
            left={
              <IconCircle
                name={n.outcome !== 'signed' ? 'close-circle-outline' : n.kind === 'signIn' ? 'log-in-outline' : n.kind === 'grant' ? 'key-outline' : 'document-text-outline'}
                tone={n.outcome === 'signed' ? 'primary' : n.outcome === 'failed' ? 'danger' : 'neutral'}
              />
            }
            title={n.title}
            subtitle={`${siteName(n.origin)} · ${n.outcome === 'signed' ? 'signed' : n.outcome === 'declined' ? 'declined' : 'failed'} ${relativeTime(n.at)}${n.message ? ` · ${n.message}` : ''}`}
          />
        ))}
      </Panel>

      <Panel title="Appearance" padded>
        <Row between gap={16}>
          <Sub>Theme</Sub>
          <Segmented
            options={[
              { value: 'system', label: 'System' },
              { value: 'light', label: 'Light' },
              { value: 'dark', label: 'Dark' },
            ]}
            value={settings.theme}
            onChange={(theme) => setSettings({ theme })}
          />
        </Row>
      </Panel>

      <Panel title="Advanced" info="Shows keys, signature schemes, the identity's time locks and recovery threshold, contract IDs and chain capacity. When this is off, the app manages keys by itself and identities keep the default time locks." padded>
        <Row between gap={16}>
          <span className="stack grow" style={{ gap: 2 }}>
            <Sub>Show keys and technical details</Sub>
          </span>
          <Switch checked={settings.advanced} onChange={(v) => setSettings({ advanced: v })} />
        </Row>
      </Panel>

      {settings.advanced ? (
        <>
          <Panel title="Keys on this computer" action="New key" onAction={() => setKeyOpen(true)}>
            <div style={{ padding: '14px 16px', paddingBottom: keys.length ? 4 : 14 }}>
              <Caption>{KEYSTORE_NOTE}</Caption>
              {keys.length === 0 ? <Caption style={{ marginTop: 6 }}>No keys yet; creating a user makes one.</Caption> : null}
              {keyProblem ? <Banner tone="danger">{sentence(keyProblem)}</Banner> : null}
            </div>
            {keys.map((k) => {
              const { used, unknown } = usedBy(k);
              return (
                <ListItem
                  key={k.id}
                  title={k.name}
                  subtitle={`${SCHEME_LABEL[k.scheme]} · ${shortId(k.pub, 10, 6)} · ${used.length ? used.join(', ') : unknown.length ? `may sign for ${unknown.join(', ')}` : 'unused'}`}
                  right={
                    used.length > 0 || unknown.length > 0 ? null : (
                      <Row>
                        <Button title="Add as user" kind="ghost" small onClick={() => addUser({ id: uuid(), name: k.name, color: nextColor(users), kind: 'key', keyId: k.id })} />
                        <Button
                          title="Delete…"
                          kind="ghost"
                          small
                          onClick={async () => {
                            if (!(await confirm({ title: `Delete key ${k.name}?`, body: 'Its secret is erased from this computer and cannot be brought back. Anything its own account holds can then never be spent.', confirm: 'Delete key', back: 'Keep it', danger: true })))
                              return;
                            setKeyProblem(null);
                            try {
                              await deleteSecret(k.id);
                              removeKey(k.id);
                            } catch (e) {
                              setKeyProblem(`${k.name} was not deleted: ${(e as Error).message}`);
                            }
                          }}
                        />
                      </Row>
                    )
                  }
                />
              );
            })}
          </Panel>

          {w.info ? (
            <>
              <Panel title="Chain">
                <div style={{ padding: '14px 16px', paddingBottom: 4 }}>
                  <Caption>
                    Capacity {w.info.status.limits.map((l) => l.toLocaleString()).join(' / ')} (C / S / D / G) per block. Standard contracts are canonical
                    deployments derived from the chain id.
                  </Caption>
                </div>
                {STANDARD.map((s) => (
                  <ListItem
                    key={s.name}
                    title={s.label}
                    subtitle={<IdChip value={hex(canonicalId(w.info!.status.chainId, contractCode(s.name)))} />}
                    right={deployed[s.name] === undefined ? null : <Pill label={deployed[s.name] ? 'deployed' : 'missing'} tone={deployed[s.name] ? 'success' : 'danger'} small />}
                  />
                ))}
              </Panel>
            </>
          ) : null}
        </>
      ) : null}

      <Panel title="This computer" padded>
        <Caption>
          {users.length} user{users.length === 1 ? '' : 's'}, {keys.length} key{keys.length === 1 ? '' : 's'}. {KEYSTORE_NOTE}
        </Caption>
        <div>
          <Button
            title="Delete everything on this computer…"
            kind="danger"
            small
            onClick={async () => {
              const yes = await confirm({
                title: 'Delete everything on this computer?',
                body: 'Users, contacts, keys, settings and activity are deleted from this computer. Identities and funds stay on chain, but a key that only this computer holds is gone for good.',
                confirm: 'Delete everything',
                back: 'Keep everything',
                danger: true,
                typeToConfirm: 'delete everything',
              });
              if (!yes) return;
              setWipeError(null);
              try {
                const kept = await deleteEverything();
                if (kept.length > 0) setWipeError(`The keystore did not delete ${kept.join(', ')}: ${kept.length === 1 ? 'that key is' : 'those keys are'} still on this computer. Delete everything again to remove ${kept.length === 1 ? 'it' : 'them'}.`);
              } catch (e) {
                setWipeError(`Not everything was deleted: ${(e as Error).message}`);
              }
            }}
          />
        </div>
        {wipeError ? <Banner tone="danger">{sentence(wipeError)}</Banner> : null}
      </Panel>

      <Dialog open={keyOpen} onClose={() => setKeyOpen(false)} title="New key">
        <Field label="Name" value={keyName} onChangeText={setKeyName} placeholder="Backup" autoFocus />
        <Segmented options={SCHEMES.map((s) => ({ value: s, label: SCHEME_LABEL[s] }))} value={scheme} onChange={setScheme} />
        <Caption>{SCHEME_HINT[scheme]}</Caption>
        {keyError ? <Banner tone="danger">{sentence(keyError)}</Banner> : null}
        <Button
          title="Create"
          loading={creating}
          onClick={() => {
            if (creating) return;
            setCreating(true);
            createDeviceKey(keyName.trim() || `Key ${keys.length + 1}`, scheme)
              .then(() => {
                setKeyName('');
                setKeyOpen(false);
              })
              .catch((e) => setKeyError((e as Error).message))
              .finally(() => setCreating(false));
          }}
        />
      </Dialog>
    </Page>
  );
}
