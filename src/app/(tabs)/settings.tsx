import React, { useEffect, useState } from 'react';
import { Pressable, Switch, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { hex, isHex } from '@/chain/bytes';
import { contractCode } from '@/chain/contracts';
import { formatAmount, relativeTime, shortId } from '@/chain/format';
import { SCHEMES, SCHEME_HINT, SCHEME_LABEL, Scheme } from '@/chain/keys';
import { canonicalId } from '@/chain/tx';
import { knownCoinsFor } from '@/config/known-coins';
import { fetchCoin, isStandardCoin, useDiscoveredCoins } from '@/state/coins';
import { DEFAULT_SCHEME, createDeviceKey, deleteEverything } from '@/state/keys';
import { deleteSecret, KEYSTORE_NOTE, uuid } from '@/state/keystore';
import { keyUsers } from '@/state/registry';
import { useIdentities } from '@/state/chain';
import { nextColor, useStore } from '@/state/store';
import type { KeyMeta } from '@/state/types';
import { useWorkspace } from '@/state/workspace';
import { CoinMark } from '@/ui/CoinPicker';
import { FeeSettings } from '@/ui/FeeSettings';
import { NodeSettings } from '@/ui/NodeSettings';
import { PhraseCard } from '@/ui/PhraseCard';
import { SecuritySettings } from '@/ui/SecuritySettings';
import { sentence, siteName } from '@/ui/describe';
import { Banner, Button, Caption, Card, Field, IconCircle, IdChip, ListItem, Pill, Row, Screen, SectionHeader, Segmented, Sheet, Sub } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { spacing, useTheme } from '@/ui/theme';

const STANDARD: { name: Parameters<typeof contractCode>[0]; label: string }[] = [
  { name: 'policy-secp256k1', label: 'secp256k1 policy' },
  { name: 'policy-p256', label: 'P-256 policy' },
  { name: 'policy-multisig-secp256k1', label: 'Multisig policy' },
  { name: 'id-registry', label: 'Identity registry' },
];

export default function SettingsScreen() {
  const t = useTheme();
  const w = useWorkspace();
  const confirm = useConfirm();
  const allSignatures = useStore((s) => s.signatures);
  const clearSignatures = useStore((s) => s.clearSignatures);
  const signatures = allSignatures.filter((n) => n.userId === w.user?.id);
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
  const [coinInput, setCoinInput] = useState('');
  const [coinError, setCoinError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [deployed, setDeployed] = useState<Record<string, boolean>>({});
  const [keySheet, setKeySheet] = useState(false);
  const [keyName, setKeyName] = useState('');
  const [scheme, setScheme] = useState<Scheme>(DEFAULT_SCHEME);
  const [keyError, setKeyError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [keyProblem, setKeyProblem] = useState<string | null>(null);
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
    <Screen title="Settings">
      <SectionHeader title="Coins" info="Follow any coin that runs the standard coin contract by its contract id. The wallet verifies the contract's code before adding it." />
      <Card padded={false} style={{ gap: 0 }}>
        {w.coins.length === 0 && w.missingCoins.length === 0 ? (
          <View style={{ padding: spacing.lg }}>
            <Caption>No coins followed yet.</Caption>
          </View>
        ) : null}
        {w.coins.map((c, i) => (
          <View key={c.hex} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem
              left={<CoinMark coin={c} />}
              title={c.config.name}
              subtitle={
                <Row gap={6}>
                  <Caption>{c.config.symbol}</Caption>
                  <IdChip value={c.hex} />
                </Row>
              }
              right={
                <Pressable
                  accessibilityLabel={`Unfollow ${c.config.name}`}
                  onPress={async () => {
                    const yes = await confirm({
                      title: `Unfollow ${c.config.name}?`,
                      body: 'It disappears from this wallet. What you hold stays on chain, and you can follow it again here.',
                      confirm: 'Unfollow',
                      back: 'Keep following',
                      danger: true,
                    });
                    if (yes) removeCoin(c.hex);
                  }}
                  hitSlop={8}
                  style={{ padding: 6 }}>
                  <Ionicons name="trash-outline" size={20} color={t.muted} />
                </Pressable>
              }
            />
          </View>
        ))}
        {w.missingCoins.map((h) => (
          <View key={h} style={{ borderTopWidth: 1, borderTopColor: t.border }}>
            <ListItem title={shortId(h, 10, 6)} subtitle="not on this chain" right={<Button title="Remove" kind="ghost" small onPress={() => removeCoin(h)} />} />
          </View>
        ))}
        <View style={{ padding: spacing.lg, gap: spacing.sm, borderTopWidth: 1, borderTopColor: t.border }}>
          <Field
            label="Add a coin by contract ID"
            value={coinInput}
            onChangeText={setCoinInput}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="64 hex digits"
            error={coinError}
          />
          <Button title="Add" kind="secondary" small icon="add" loading={adding} disabled={!isHex(coinInput, 32)} onPress={() => add(coinInput)} />
        </View>
      </Card>
      {known.length > 0 || found.length > 0 ? (
        <>
          <SectionHeader title="Suggested" icon="sparkles-outline" />
          <Card padded={false} style={{ gap: 0 }}>
            {known.map((k, i) => (
              <View key={k.contract} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                <ListItem title={k.name} subtitle="known on this chain" right={<Button title="Follow" kind="secondary" small onPress={() => add(k.contract)} />} />
              </View>
            ))}
            {found.map((f, i) => (
              <View key={f.info.hex} style={{ borderTopWidth: i === 0 && known.length === 0 ? 0 : 1, borderTopColor: t.border }}>
                <ListItem
                  left={<CoinMark coin={f.info} />}
                  title={f.info.config.name}
                  subtitle={`you hold ${formatAmount(f.total, f.info.config.decimals)} ${f.info.config.symbol}`}
                  right={<Button title="Follow" kind="secondary" small onPress={() => addCoin(f.info.hex)} />}
                />
              </View>
            ))}
          </Card>
        </>
      ) : null}

      <FeeSettings w={w} />

      <NodeSettings w={w} missing={missingContracts.map((c) => c.label)} />

      <SectionHeader
        title="Signed for sites"
        info="Requests of sites this user answered on this device: sign-ins, permissions and statements. Scan a site's code from the wallet to sign in with Ting ID."
        action={signatures.length > 0 ? 'Clear' : undefined}
        onAction={clearSignatures}
      />
      <Card padded={false} style={{ gap: 0 }}>
        {signatures.length === 0 ? (
          <View style={{ padding: spacing.lg }}>
            <Caption>Nothing signed for a site yet.</Caption>
          </View>
        ) : null}
        {signatures.slice(0, 30).map((n, i) => (
          <View key={`${n.at}-${i}`} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem
              left={
                <IconCircle
                  name={n.outcome !== 'signed' ? 'close-circle-outline' : n.kind === 'signIn' ? 'log-in-outline' : n.kind === 'grant' ? 'key-outline' : 'document-text-outline'}
                  color={n.outcome === 'signed' ? t.primary : n.outcome === 'failed' ? t.danger : t.muted}
                  bg={n.outcome === 'signed' ? t.primarySoft : n.outcome === 'failed' ? t.dangerSoft : t.cardAlt}
                />
              }
              title={n.title}
              subtitle={`${siteName(n.origin)} · ${n.outcome} ${relativeTime(n.at)}${n.message ? ` · ${n.message}` : ''}`}
            />
          </View>
        ))}
      </Card>

      <SectionHeader title="Appearance" />
      <Card>
        <Segmented
          options={[
            { value: 'system', label: 'System' },
            { value: 'light', label: 'Light' },
            { value: 'dark', label: 'Dark' },
          ]}
          value={settings.theme}
          onChange={(theme) => setSettings({ theme })}
        />
      </Card>

      <SectionHeader title="Advanced" info="Shows keys, signature schemes, the identity's time locks and recovery threshold, contract IDs and chain capacity. When this is off, the app manages keys by itself and identities keep the default time locks." />
      <Card>
        <Row style={{ justifyContent: 'space-between' }}>
          <View style={{ flex: 1, gap: 2 }}>
            <Sub>Show keys and technical details</Sub>
          </View>
          <Switch accessibilityLabel="Show keys and technical details" value={settings.advanced} onValueChange={(v) => setSettings({ advanced: v })} trackColor={{ true: t.primary, false: t.border }} />
        </Row>
      </Card>

      <SectionHeader title="Coin tools" info="A Coins tab that lists every coin on the chain, creates new coins, and mints coins whose minter is one of your accounts. Off, Ting ID shows only the coins you follow." />
      <Card>
        <Row style={{ justifyContent: 'space-between' }}>
          <View style={{ flex: 1, gap: 2 }}>
            <Sub>Browse, create and mint coins</Sub>
          </View>
          <Switch accessibilityLabel="Browse, create and mint coins" value={!!settings.coinTools} onValueChange={(v) => setSettings({ coinTools: v })} trackColor={{ true: t.primary, false: t.border }} />
        </Row>
      </Card>

      {settings.advanced ? (
        <>
          <SectionHeader title="Keys on this device" action="New key" onAction={() => setKeySheet(true)} />
          <Card padded={false} style={{ gap: 0 }}>
            <View style={{ padding: spacing.lg, paddingBottom: keys.length ? 4 : spacing.lg }}>
              <Caption>{KEYSTORE_NOTE}</Caption>
              {keys.length === 0 ? <Caption style={{ marginTop: 6 }}>No keys yet; creating a user makes one.</Caption> : null}
              {keyProblem ? <Banner tone="danger">{sentence(keyProblem)}</Banner> : null}
            </View>
            {keys.map((k, i) => {
              const { used, unknown } = usedBy(k);
              return (
                <View key={k.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                  <ListItem
                    title={k.name}
                    subtitle={`${SCHEME_LABEL[k.scheme]} · ${shortId(k.pub, 10, 6)} · ${used.length ? used.join(', ') : unknown.length ? `may sign for ${unknown.join(', ')}` : 'unused'}`}
                    right={
                      used.length > 0 || unknown.length > 0 ? null : (
                        <Row>
                          <Button title="Add as user" kind="ghost" small onPress={() => addUser({ id: uuid(), name: k.name, color: nextColor(users), kind: 'key', keyId: k.id })} />
                          <Button
                            title="Delete…"
                            kind="ghost"
                            small
                            onPress={async () => {
                              const yes = await confirm({ title: `Delete key ${k.name}?`, body: 'Its secret is erased from this device and cannot be brought back. Anything its own account holds can then never be spent.', confirm: 'Delete key', back: 'Keep it', danger: true });
                              if (!yes) return;
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
                </View>
              );
            })}
          </Card>

          {w.info ? (
            <>
              <SectionHeader title="Chain" />
              <Card padded={false} style={{ gap: 0 }}>
                <View style={{ padding: spacing.lg, paddingBottom: 4 }}>
                  <Caption>
                    Capacity {w.info.status.limits.map((l) => l.toLocaleString()).join(' / ')} (C / S / D / G) per block. Standard contracts are
                    canonical deployments derived from the chain id.
                  </Caption>
                </View>
                {STANDARD.map((s) => (
                  <View key={s.name} style={{ borderTopWidth: 1, borderTopColor: t.border }}>
                    <ListItem
                      title={s.label}
                      subtitle={<IdChip value={hex(canonicalId(w.info!.status.chainId, contractCode(s.name)))} />}
                      right={deployed[s.name] === undefined ? null : <Pill label={deployed[s.name] ? 'deployed' : 'missing'} tone={deployed[s.name] ? 'success' : 'danger'} small />}
                    />
                  </View>
                ))}
              </Card>
            </>
          ) : null}
        </>
      ) : null}

      <SecuritySettings />

      {w.user ? <PhraseCard user={w.user} identity={w.identity} loading={w.identityLoading} c={w.info?.canonical ?? null} keys={w.keys} /> : null}

      <SectionHeader title="This device" />
      <Card>
        <Caption>
          {users.length} user{users.length === 1 ? '' : 's'}, {keys.length} key{keys.length === 1 ? '' : 's'}. {KEYSTORE_NOTE}
        </Caption>
        <Button
          title="Delete everything on this device…"
          kind="danger"
          small
          onPress={async () => {
            const yes = await confirm({
              title: 'Delete everything on this device?',
              body: 'Users, contacts, keys, recovery phrases, settings and activity are deleted from this device. Identities and funds stay on chain: write down your recovery phrases first, as a key that only this device holds is gone for good.',
              confirm: 'Delete everything',
              back: 'Keep everything',
              danger: true,
              typeToConfirm: 'delete everything',
            });
            if (!yes) return;
            setWipeError(null);
            try {
              const kept = await deleteEverything();
              if (kept.length > 0) setWipeError(`The keystore did not delete ${kept.join(', ')}: still on this device. Delete everything again to remove ${kept.length === 1 ? 'it' : 'them'}.`);
            } catch (e) {
              setWipeError(`Not everything was deleted: ${(e as Error).message}`);
            }
          }}
        />
        {wipeError ? <Banner tone="danger">{sentence(wipeError)}</Banner> : null}
      </Card>

      <Sheet visible={keySheet} onClose={() => setKeySheet(false)} title="New key">
        <Field label="Name" value={keyName} onChangeText={setKeyName} placeholder="Backup" />
        <Segmented options={SCHEMES.map((s) => ({ value: s, label: SCHEME_LABEL[s] }))} value={scheme} onChange={setScheme} />
        <Caption>{SCHEME_HINT[scheme]}</Caption>
        {keyError ? <Banner tone="danger">{sentence(keyError)}</Banner> : null}
        <Button
          title="Create"
          loading={creating}
          onPress={() => {
            if (creating) return;
            setCreating(true);
            createDeviceKey(keyName.trim() || `Key ${keys.length + 1}`, scheme)
              .then(() => {
                setKeyName('');
                setKeySheet(false);
              })
              .catch((e) => setKeyError((e as Error).message))
              .finally(() => setCreating(false));
          }}
        />
      </Sheet>
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
