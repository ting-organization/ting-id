import React, { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import * as Clipboard from 'expo-clipboard';
import { isHex } from '@/chain/bytes';
import { shortId } from '@/chain/format';
import { useChainCache } from '@/state/cache';
import { uuid } from '@/state/keystore';
import { useActiveUser, useStore } from '@/state/store';
import type { Contact, User } from '@/state/types';
import { Avatar, Banner, Button, Caption, Card, Field, IconCircle, IconName, ListItem, Screen, SectionHeader, Sheet } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { RowGroup, userLine } from '@/ui/Profile';
import { spacing, useTheme } from '@/ui/theme';
import { forgetUser } from '@/state/keys';
import { sentence } from '@/ui/describe';

/** The person a menu is open for. */
type Menu = { user: User } | { contact: Contact } | null;

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
  const [menu, setMenu] = useState<Menu>(null);

  const removeUser = async (u: User) => {
    // Words not written down are only on this phone: removing the user deletes them.
    const unwritten = !!u.phrase && !u.phraseBackedUp;
    if (unwritten) {
      const write = await confirm({
        title: `${u.name}’s recovery phrase is not written down`,
        body: 'This phone holds the only copy of its words, and removing the user deletes them. Write them down first.',
        confirm: 'Write it down',
        back: 'Remove anyway…',
      });
      if (write) return router.push({ pathname: '/phrase/[id]', params: { id: u.id } });
    }
    const yes = await confirm({
      title: `Remove ${u.name} from this phone?`,
      body: unwritten
        ? 'Its recovery phrase is deleted with it, and no written copy exists. Without the words or guardians who can recover it, the identity and its funds are gone for good.'
        : u.phrase
          ? 'Its funds stay on chain, and its recovery phrase is deleted from this phone: with your written copy, Restore adds it again.'
          : u.kind === 'key'
            ? 'Its funds stay on chain, and only its key can spend them. The key stays on this phone: with the advanced settings on, Settings → Keys can add it as a user again.'
            : 'Its funds stay on chain. This phone’s key for it stays: restoring the identity by its ID uses it again.',
      confirm: 'Remove',
      back: 'Keep',
      danger: true,
      typeToConfirm: unwritten ? 'remove' : undefined,
    });
    if (!yes) return;
    setRemoveError(null);
    forgetUser(u).catch((e) => setRemoveError(`${u.name} was not removed: ${(e as Error).message}`));
  };

  // The row with its menu button beside it, not inside it: one control does not hold another.
  const withMenu = (row: React.ReactNode, label: string, onMenu: () => void, selected?: boolean) => (
    <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: selected ? t.primarySoft : 'transparent' }}>
      <View style={{ flex: 1 }}>{row}</View>
      <Pressable
        onPress={onMenu}
        hitSlop={8}
        accessibilityRole="button"
        accessibilityLabel={label}
        style={({ pressed }) => ({ paddingVertical: 8, paddingLeft: 4, paddingRight: spacing.lg, opacity: pressed ? 0.6 : 1 })}>
        <Ionicons name="ellipsis-horizontal" size={20} color={t.muted} />
      </Pressable>
    </View>
  );

  const close = () => setMenu(null);
  const menuUser = menu && 'user' in menu ? menu.user : null;
  const menuContact = menu && 'contact' in menu ? menu.contact : null;

  return (
    <Screen title="People">
      <SectionHeader
        title="Users on this phone"
        info="Each user is an identity, or an account of a single key. Tap one to make it active. Removing one only forgets it on this phone; its funds stay on chain."
        action="Add"
        onAction={() => router.push('/user/new')}
      />
      {removeError ? <Banner tone="danger">{sentence(removeError)}</Banner> : null}
      <Card padded={false} style={{ gap: 0 }}>
        {users.length === 0 ? <Caption style={{ padding: spacing.lg }}>No users yet.</Caption> : null}
        {users.map((u, i) => {
          const on = u.id === active?.id;
          return (
            <View key={u.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
              {withMenu(
                <ListItem
                  left={<Avatar name={u.name} color={u.color} />}
                  title={u.name}
                  subtitle={<Caption numberOfLines={1}>{on ? `Active · ${userLine(u)}` : userLine(u)}</Caption>}
                  selected={on}
                  onPress={() => setActive(u.id)}
                  right={on ? <Ionicons name="checkmark-circle" size={20} color={t.primary} /> : null}
                />,
                `More for ${u.name}`,
                () => setMenu({ user: u }),
                on,
              )}
            </View>
          );
        })}
      </Card>

      <SectionHeader title="Contacts" info="Accounts you send to, by name. Save one from Send or Review when you pay a new account." action="Add" onAction={() => setContactSheet(true)} />
      <Card padded={false} style={{ gap: 0 }}>
        {contacts.length === 0 ? <Caption style={{ padding: spacing.lg }}>No contacts yet. Accounts you pay can be saved here by name.</Caption> : null}
        {contacts.map((c, i) => (
          <View key={c.id} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
            {withMenu(
              <ListItem
                left={<Avatar name={c.name} color={t.primary} soft />}
                title={c.name}
                subtitle={<Caption numberOfLines={1}>{`${shortId(c.account, 10, 8)}${c.chain && c.chain !== chain ? ' · another network' : ''}`}</Caption>}
                onPress={() => setMenu({ contact: c })}
              />,
              `More for ${c.name}`,
              () => setMenu({ contact: c }),
            )}
          </View>
        ))}
      </Card>

      <Sheet visible={menu !== null} onClose={close} title={menuUser?.name ?? menuContact?.name}>
        {menuUser ? (
          <RowGroup>
            {menuUser.id !== active?.id ? (
              <MenuRow
                icon="swap-horizontal"
                title="Make active"
                onPress={() => {
                  setActive(menuUser.id);
                  close();
                }}
              />
            ) : null}
            {menuUser.kind === 'identity' && menuUser.identityId ? (
              <MenuRow
                icon="copy-outline"
                title="Copy identity ID"
                onPress={() => {
                  Clipboard.setStringAsync(menuUser.identityId!);
                  close();
                }}
              />
            ) : null}
            <MenuRow
              icon="trash-outline"
              title="Remove from this phone…"
              danger
              onPress={() => {
                close();
                removeUser(menuUser);
              }}
            />
          </RowGroup>
        ) : null}
        {menuContact ? (
          <>
            <Caption numberOfLines={2}>{menuContact.account}</Caption>
            <RowGroup>
              <MenuRow
                icon="arrow-up"
                title={`Send to ${menuContact.name}`}
                onPress={() => {
                  close();
                  router.push({ pathname: '/send', params: { to: menuContact.account } });
                }}
              />
              <MenuRow
                icon="copy-outline"
                title="Copy account"
                onPress={() => {
                  Clipboard.setStringAsync(menuContact.account);
                  close();
                }}
              />
              <MenuRow
                icon="trash-outline"
                title="Remove contact…"
                danger
                onPress={async () => {
                  close();
                  if (await confirm({ title: `Remove contact ${menuContact.name}?`, confirm: 'Remove', back: 'Keep', danger: true })) removeContact(menuContact.id);
                }}
              />
            </RowGroup>
          </>
        ) : null}
      </Sheet>

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

function MenuRow({ icon, title, onPress, danger }: { icon: IconName; title: string; onPress: () => void; danger?: boolean }) {
  const t = useTheme();
  return (
    <ListItem
      left={<IconCircle name={icon} color={danger ? t.danger : t.primary} bg={danger ? t.dangerSoft : t.primarySoft} size={34} />}
      title={title}
      onPress={onPress}
    />
  );
}
