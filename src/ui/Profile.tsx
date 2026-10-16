// The profile button: the active user's avatar, opening a sheet with the
// user, the way to its identity and the settings, the other users on the
// phone to switch to, and the way to add one.
import React, { Children, ReactNode, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { shortId } from '@/chain/format';
import { useActiveUser, useStore } from '@/state/store';
import type { User } from '@/state/types';
import { Avatar, Caption, IconCircle, IdChip, ListItem, Row, Sheet } from './components';
import { radius, spacing, type, useTheme } from './theme';

export function userKindLabel(kind: 'identity' | 'key'): string {
  return kind === 'identity' ? 'Identity' : 'Simple account';
}

/** What a user is, in one line: its identity's short ID, or a simple account. */
export function userLine(u: User): string {
  return u.kind === 'identity' ? `Identity ${shortId(u.identityId ?? '', 6, 4)}` : 'Simple account';
}

export function ProfileButton({ size = 38 }: { size?: number }) {
  const t = useTheme();
  const active = useActiveUser();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Pressable
        onPress={() => setOpen(true)}
        hitSlop={6}
        accessibilityRole="button"
        accessibilityLabel={active ? `Users: ${active.name}` : 'Users'}
        style={({ pressed }) => ({ opacity: pressed ? 0.75 : 1 })}>
        {active ? (
          <Avatar name={active.name} color={active.color} size={size} />
        ) : (
          <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }}>
            <Ionicons name="person-outline" size={size * 0.5} color={t.muted} />
          </View>
        )}
      </Pressable>
      <ProfileSheet visible={open} onClose={() => setOpen(false)} />
    </>
  );
}

/** Rows in one bordered box, a hairline between them. */
export function RowGroup({ children }: { children: ReactNode }) {
  const t = useTheme();
  const rows = Children.toArray(children).filter(Boolean);
  return (
    <View style={{ borderWidth: 1, borderColor: t.border, borderRadius: radius.lg, overflow: 'hidden' }}>
      {rows.map((row, i) => (
        <View key={i} style={{ borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
          {row}
        </View>
      ))}
    </View>
  );
}

export function ProfileSheet({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const t = useTheme();
  const router = useRouter();
  const users = useStore((s) => s.users);
  const setActive = useStore((s) => s.setActiveUser);
  const active = useActiveUser();
  const others = users.filter((u) => u.id !== active?.id);
  const go = (path: '/identity' | '/settings' | '/user/new') => {
    onClose();
    router.push(path);
  };
  return (
    <Sheet visible={visible} onClose={onClose}>
      {active ? (
        <Row gap={14}>
          <Avatar name={active.name} color={active.color} size={52} />
          <View style={{ flex: 1, gap: 6 }}>
            <Text style={[type.heading, { color: t.text }]} numberOfLines={1}>
              {active.name}
            </Text>
            {active.kind === 'identity' && active.identityId ? <IdChip label="ID" value={active.identityId} /> : <Caption>{userKindLabel(active.kind)}</Caption>}
          </View>
        </Row>
      ) : (
        <Caption>No user on this phone yet.</Caption>
      )}
      {active ? (
        <RowGroup>
          <ListItem left={<IconCircle name="finger-print-outline" color={t.primary} bg={t.primarySoft} size={36} />} title="Identity" chevron onPress={() => go('/identity')} />
          <ListItem left={<IconCircle name="settings-outline" color={t.primary} bg={t.primarySoft} size={36} />} title="Settings" chevron onPress={() => go('/settings')} />
        </RowGroup>
      ) : null}
      {others.length > 0 ? (
        <View style={{ gap: spacing.xs }}>
          <Caption style={{ paddingHorizontal: 4 }}>Switch to</Caption>
          <RowGroup>
            {others.map((u) => (
              <ListItem
                key={u.id}
                left={<Avatar name={u.name} color={u.color} size={36} />}
                title={u.name}
                subtitle={<Caption numberOfLines={1}>{userLine(u)}</Caption>}
                onPress={() => {
                  setActive(u.id);
                  onClose();
                }}
              />
            ))}
          </RowGroup>
        </View>
      ) : null}
      <RowGroup>
        <ListItem left={<IconCircle name="person-add-outline" color={t.muted} bg={t.cardAlt} size={36} />} title="Add a user" onPress={() => go('/user/new')} />
      </RowGroup>
    </Sheet>
  );
}
