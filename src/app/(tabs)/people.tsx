import React, { useState } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { isHex } from '@/chain/bytes';
import { shortId } from '@/chain/format';
import { useChainCache } from '@/state/cache';
import { uuid } from '@/state/keystore';
import { useActiveUser, useStore } from '@/state/store';
import { Avatar, Banner, Button, Caption, Card, Field, ListItem, Pill, Row, Screen, SectionHeader, Sheet } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { spacing, useTheme } from '@/ui/theme';
import { forgetUser } from '@/state/keys';
import { sentence } from '@/ui/describe';

export default function PeopleScreen() {
  const t = useTheme();
  const chain = useChainCache()?.chain ?? null;
  const router = useRouter();
  const users = useStore((s) => s.users);
  const contacts = useStore((s) => s.contacts);
  const active = useActiveUser();
  const setActive = useStore((s) => s.setActiveUser);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const addContact = useStore((s) => s.addContact);
  const removeContact = useStore((s) => s.removeContact);
  const confirm = useConfirm();
  const [contactSheet, setContactSheet] = useState(false);
  const [contactName, setContactName] = useState('');
  const [contactAccount, setContactAccount] = useState('');

  return (
    <Screen title="People">
      <SectionHeader
        title="Users on this device"
        info="Each user is an identity, or an account of a single key. Removing one here only forgets it on this device; its funds stay on chain."
        action="Add"
        onAction={() => router.push('/user/new')}
      />
      {removeError ? <Banner tone="danger">{sentence(removeError)}</Banner> : null}
      <Card padded={false} style={{ gap: 0 }}>
        {users.length === 0 ? <Caption style={{ padding: spacing.lg }}>No users yet.</Caption> : null}
        {users.map((u, i) => (
          <View key={u.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem
              left={<Avatar name={u.name} color={u.color} />}
              title={u.name}
              subtitle={u.kind === 'identity' ? `Identity ${shortId(u.identityId ?? '', 6, 4)}` : 'Simple account'}
              selected={u.id === active?.id}
              onPress={() => setActive(u.id)}
              right={
                <Row>
                  {u.id === active?.id ? <Pill label="active" tone="primary" small /> : null}
                  <Button
                    title="Remove…"
                    kind="ghost"
                    small
                    onPress={async () => {
                      const yes = await confirm({
                        title: `Remove ${u.name} from this device?`,
                        body: u.phrase
                          ? 'Its funds stay on chain, and its recovery phrase is deleted from this device: with your written copy, Restore adds it again.'
                          : u.kind === 'key'
                            ? 'Its funds stay on chain, and only its key can spend them. The key stays on this device: with the advanced settings on, Settings → Keys can add it as a user again.'
                            : 'Its funds stay on chain. This device’s key for it stays: restoring the identity by its ID uses it again.',
                        confirm: 'Remove',
                        back: 'Keep',
                        danger: true,
                      });
                      if (!yes) return;
                      setRemoveError(null);
                      forgetUser(u).catch((e) => setRemoveError(`${u.name} was not removed: ${(e as Error).message}`));
                    }}
                  />
                </Row>
              }
            />
          </View>
        ))}
      </Card>

      <SectionHeader title="Contacts" info="Accounts you send to often, by name." action="Add" onAction={() => setContactSheet(true)} />
      <Card padded={false} style={{ gap: 0 }}>
        {contacts.length === 0 ? <Caption style={{ padding: spacing.lg }}>No contacts yet.</Caption> : null}
        {contacts.map((c, i) => (
          <View key={c.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            <ListItem
              left={<Avatar name={c.name} color={t.primary} soft />}
              title={c.name}
              subtitle={`${shortId(c.account, 10, 8)}${c.chain && c.chain !== chain ? ' · another network' : ''}`}
              right={
                <Button
                  title="Remove…"
                  kind="ghost"
                  small
                  onPress={async () => {
                    if (await confirm({ title: `Remove contact ${c.name}?`, confirm: 'Remove', back: 'Keep', danger: true })) removeContact(c.id);
                  }}
                />
              }
            />
          </View>
        ))}
      </Card>

      <Sheet visible={contactSheet} onClose={() => setContactSheet(false)} title="New contact">
        <Field label="Name" value={contactName} onChangeText={setContactName} placeholder="Bob" />
        <Field label="Account" value={contactAccount} onChangeText={setContactAccount} placeholder="64 hex digits" autoCapitalize="none" autoCorrect={false} />
        <Button
          title="Save"
          disabled={!contactName.trim() || !isHex(contactAccount, 32)}
          onPress={() => {
            addContact({ id: uuid(), name: contactName.trim(), account: contactAccount.trim().toLowerCase() });
            setContactName('');
            setContactAccount('');
            setContactSheet(false);
          }}
        />
      </Sheet>
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
