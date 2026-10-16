import React from 'react';
import { Tabs } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useStore } from '@/state/store';
import { font, useTheme } from '@/ui/theme';

export default function TabsLayout() {
  const t = useTheme();
  // The Coins tab is there only with the coin tools switched on in Settings.
  const coinTools = useStore((s) => !!s.settings.coinTools);
  // Tall enough for an icon over a label in the app's font, above the system's
  // navigation bar or gesture area. Some phones and emulators report no such
  // area, and the default height then cuts the labels off.
  const insets = useSafeAreaInsets();
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: t.ink,
        tabBarInactiveTintColor: t.faint,
        tabBarStyle: { backgroundColor: t.card, borderTopColor: t.border, height: 64 + insets.bottom, paddingTop: 4, paddingBottom: 4 + insets.bottom },
        tabBarLabelStyle: { ...font.bold, fontSize: 11, lineHeight: 15 },
        sceneStyle: { backgroundColor: t.background },
      }}>
      <Tabs.Screen
        name="index"
        options={{ title: 'Wallet', tabBarIcon: ({ color, size }) => <Ionicons name="wallet-outline" size={size} color={color} /> }}
      />
      <Tabs.Screen
        name="send"
        options={{ title: 'Send', tabBarIcon: ({ color, size }) => <Ionicons name="paper-plane-outline" size={size} color={color} /> }}
      />
      <Tabs.Screen
        name="coins"
        options={{ title: 'Coins', href: coinTools ? undefined : null, tabBarIcon: ({ color, size }) => <Ionicons name="layers-outline" size={size} color={color} /> }}
      />
      <Tabs.Screen
        name="identity"
        options={{ title: 'Identity', tabBarIcon: ({ color, size }) => <Ionicons name="finger-print-outline" size={size} color={color} /> }}
      />
      <Tabs.Screen
        name="people"
        options={{ title: 'People', tabBarIcon: ({ color, size }) => <Ionicons name="people-outline" size={size} color={color} /> }}
      />
      <Tabs.Screen
        name="settings"
        options={{ title: 'Settings', tabBarIcon: ({ color, size }) => <Ionicons name="settings-outline" size={size} color={color} /> }}
      />
    </Tabs>
  );
}
