// What the app shows when its saved state could not be read. Nothing is
// overwritten until the person chooses to start fresh.
import React, { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { startFresh } from '@/state/store';
import { Banner, Button, Caption } from './components';
import { useConfirm } from './confirm';
import { font, spacing, useTheme } from './theme';
import { sentence } from './describe';


export function LoadError({ message }: { message: string }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const confirm = useConfirm();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    const yes = await confirm({
      title: 'Start fresh?',
      body: 'Ting ID starts without users, contacts or settings. The keys it can still find in the saved data stay listed. Identities and funds stay on chain.',
      confirm: 'Start fresh',
      back: 'Keep the saved data',
      danger: true,
      typeToConfirm: 'start fresh',
    });
    if (!yes) return;
    setBusy(true);
    setError(null);
    try {
      await startFresh();
    } catch (e) {
      setError(sentence((e as Error).message));
      setBusy(false);
    }
  };

  return (
    <ScrollView style={{ flex: 1, backgroundColor: t.background }} contentContainerStyle={{ padding: spacing.lg, paddingTop: insets.top + spacing.xl, gap: spacing.md }}>
      <Text style={{ color: t.text, fontSize: 22, ...font.extrabold }}>Ting ID could not read its saved data</Text>
      <Banner tone="danger">{sentence(message)}</Banner>
      <Caption>Nothing has been changed: your users and settings stay as they were saved, and your keys stay in the device keystore. Closing Ting ID and opening it again tries once more.</Caption>
      <View style={{ alignSelf: 'flex-start' }}>
        <Button title="Start fresh" kind="danger" onPress={start} loading={busy} />
      </View>
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
    </ScrollView>
  );
}
