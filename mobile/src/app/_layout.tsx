import React from 'react';
import {
  Manrope_400Regular,
  Manrope_500Medium,
  Manrope_600SemiBold,
  Manrope_700Bold,
  Manrope_800ExtraBold,
  useFonts,
} from '@expo-google-fonts/manrope';
import { Stack } from 'expo-router';
import { fetch as expoFetch } from 'expo/fetch';
import { StatusBar } from 'expo-status-bar';
import { Platform, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Loading } from '@/ui/components';
import { LoadError } from '@/ui/LoadError';
import { ConfirmProvider } from '@/ui/confirm';
import { font, useTheme } from '@/ui/theme';
import { setSiteFetch } from '@/chain/node';
import { useStore } from '@/state/store';
import { useCoinBootstrap } from '@/state/workspace';

// React Native's own fetch follows redirects whatever it is told; Expo's
// refuses them, which signing requests need (chain/node siteFetch).
if (Platform.OS !== 'web') {
  setSiteFetch((input, init) => expoFetch(String(input instanceof Request ? input.url : input), { ...(init as object), redirect: 'error' }) as unknown as Promise<Response>);
}

/** Runs once the store is hydrated: follows the chain's known coins. */
function Bootstrap() {
  useCoinBootstrap();
  return null;
}

export default function RootLayout() {
  const t = useTheme();
  const hydrated = useStore((s) => s.hydrated);
  const loadError = useStore((s) => s.loadError);
  // The app's font; the system's stands in if it cannot load.
  const [fontsLoaded, fontError] = useFonts({ Manrope_400Regular, Manrope_500Medium, Manrope_600SemiBold, Manrope_700Bold, Manrope_800ExtraBold });
  const fontsReady = fontsLoaded || !!fontError;
  const ready = hydrated && fontsReady;
  const header = {
    headerShown: true,
    headerStyle: { backgroundColor: t.background },
    headerTintColor: t.text,
    headerTitleStyle: { ...font.extrabold, fontSize: 17 },
    headerShadowVisible: false,
  };
  return (
    <SafeAreaProvider>
      <StatusBar style={t.dark ? 'light' : 'dark'} />
      {loadError && fontsReady ? (
        <ConfirmProvider>
          <LoadError message={loadError} />
        </ConfirmProvider>
      ) : ready ? (
        <ConfirmProvider>
          <Bootstrap />
          <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: t.background } }}>
            <Stack.Screen name="(tabs)" />
            <Stack.Screen name="coin/[id]" />
            <Stack.Screen name="coins/[id]" />
            <Stack.Screen name="coins/new" options={{ ...header, presentation: 'modal', title: 'Create a coin' }} />
            <Stack.Screen name="mint/[id]" options={{ ...header, presentation: 'modal', title: 'Mint' }} />
            <Stack.Screen name="minter/[id]" options={{ ...header, presentation: 'modal', title: 'Move minting' }} />
            <Stack.Screen name="user/new" options={{ ...header, presentation: 'modal', title: 'New user' }} />
            <Stack.Screen name="tx/[id]" options={{ ...header, presentation: 'modal', title: 'Transaction' }} />
            <Stack.Screen name="identity/propose" options={{ ...header, presentation: 'modal', title: 'Propose a change' }} />
            <Stack.Screen name="identity/recovery" options={{ ...header, presentation: 'modal', title: 'Recovery' }} />
            <Stack.Screen name="sign" options={{ ...header, presentation: 'modal', title: 'Signing request' }} />
          </Stack>
        </ConfirmProvider>
      ) : (
        <View style={{ flex: 1, backgroundColor: t.background, justifyContent: 'center' }}>
          <Loading />
        </View>
      )}
    </SafeAreaProvider>
  );
}
