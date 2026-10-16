// The profile button: the active user's avatar, opening a sheet with the
// user, the other users on the device and the way to add one.
import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { shortId } from '@/chain/format';
import { useActiveUser, useStore } from '@/state/store';
import { Avatar, Button, Caption, IdChip, ListItem, Pill, Row, Sheet } from './components';
import { radius, spacing, type, useTheme } from './theme';

export function userKindLabel(kind: 'identity' | 'key'): string {
  return kind === 'identity' ? 'Identity' : 'Simple account';
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

export function ProfileSheet({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const t = useTheme();
  const router = useRouter();
  const users = useStore((s) => s.users);
  const setActive = useStore((s) => s.setActiveUser);
  const active = useActiveUser();
  const [switching, setSwitching] = useState(false);
  const close = () => {
    setSwitching(false);
    onClose();
  };
  const others = users.filter((u) => u.id !== active?.id);
  return (
    <Sheet visible={visible} onClose={close}>
      {active ? (
        <Row gap={14}>
          <Avatar name={active.name} color={active.color} size={56} />
          <View style={{ flex: 1, gap: 4 }}>
            <Text style={[type.heading, { color: t.text }]}>{active.name}</Text>
            <Pill label={userKindLabel(active.kind)} tone="primary" small />
          </View>
        </Row>
      ) : (
        <Caption>No user on this device yet.</Caption>
      )}
      {active?.kind === 'identity' && active.identityId ? <IdChip label="ID" value={active.identityId} /> : null}
      <View style={{ height: spacing.xs }} />
      <Row>
        <Button
          title="Identity"
          kind="secondary"
          small
          icon="finger-print-outline"
          onPress={() => {
            close();
            router.push('/identity');
          }}
        />
        <Button
          title="Settings"
          kind="ghost"
          small
          icon="settings-outline"
          onPress={() => {
            close();
            router.push('/settings');
          }}
        />
      </Row>
      {others.length > 0 || switching ? (
        <View style={{ backgroundColor: t.cardAlt, borderRadius: radius.md, marginTop: spacing.sm }}>
          <Pressable onPress={() => setSwitching((s) => !s)} style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', padding: spacing.md }}>
            <Caption>Switch user ({others.length} other{others.length === 1 ? '' : 's'})</Caption>
            <Ionicons name={switching ? 'chevron-up' : 'chevron-down'} size={16} color={t.muted} />
          </Pressable>
          {switching
            ? others.map((u) => (
                <ListItem
                  key={u.id}
                  left={<Avatar name={u.name} color={u.color} size={34} />}
                  title={u.name}
                  subtitle={u.kind === 'identity' ? `identity ${shortId(u.identityId ?? '', 6, 4)}` : 'simple account'}
                  onPress={() => {
                    setActive(u.id);
                    close();
                  }}
                />
              ))
            : null}
        </View>
      ) : null}
      <Button
        title="Add a user"
        kind="ghost"
        small
        icon="person-add-outline"
        onPress={() => {
          close();
          router.push('/user/new');
        }}
      />
    </Sheet>
  );
}
