// Ting ID behind the phone's own check of its owner (Settings → Security):
// asked when the app opens, and when it comes back after a minute away. The
// lock covers everything, sheets and dialogs included, and keeps the screen
// the person was on beneath it.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Modal, Text, View } from 'react-native';
import { checkOwner, ownerCheckAvailable, ownerCheckRunning } from '@/state/ownerCheck';
import { useStore } from '@/state/store';
import { Button, Logo } from './components';
import { font, spacing, useTheme } from './theme';

/** How long the app may be away before it asks again. */
const AWAY_MS = 60_000;

export function AppLock() {
  const t = useTheme();
  const on = useStore((s) => s.settings.unlockApp !== false);
  // Locked from the start while the check is on, until the phone says it cannot check.
  const [locked, setLocked] = useState(on);
  const [problem, setProblem] = useState<string | null>(null);
  const awaySince = useRef<number | null>(null);

  const unlock = useCallback(async () => {
    setProblem(null);
    const r = await checkOwner('Unlock Ting ID');
    if (r === 'confirmed' || r === 'unavailable') setLocked(false);
    else if (r === 'failed') setProblem('The phone did not confirm it is you.');
  }, []);

  // At the start, a phone that can check asks at once; one that cannot opens.
  const asked = useRef(false);
  useEffect(() => {
    if (asked.current || !on) return;
    asked.current = true;
    ownerCheckAvailable().then((available) => (available ? unlock() : setLocked(false)));
  }, [on, unlock]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'background') {
        // The PIN screen of a check is not leaving the app.
        if (!ownerCheckRunning()) awaySince.current = Date.now();
        return;
      }
      if (state !== 'active') return;
      const since = awaySince.current;
      awaySince.current = null;
      if (!on || since === null || Date.now() - since < AWAY_MS) return;
      ownerCheckAvailable().then((available) => {
        if (!available) return;
        setLocked(true);
        unlock();
      });
    });
    return () => sub.remove();
  }, [on, unlock]);

  // Gone at once once unlocked, rather than faded out.
  if (!on || !locked) return null;
  return (
    <Modal visible animationType="fade" statusBarTranslucent onRequestClose={() => {}}>
      <View style={{ flex: 1, backgroundColor: t.header, alignItems: 'center', justifyContent: 'center', padding: spacing.xl, gap: spacing.lg }}>
        <Logo size={64} />
        <Text style={{ color: t.onHeader, fontSize: 22, letterSpacing: -0.4, ...font.extrabold }}>Ting ID is locked</Text>
        <Text style={{ color: t.onHeaderMuted, fontSize: 14, textAlign: 'center', lineHeight: 20, ...font.medium }}>
          {problem ?? 'Unlock with your fingerprint, face or screen lock.'}
        </Text>
        <Button title="Unlock" kind="light" icon="lock-open-outline" onPress={unlock} style={{ alignSelf: 'stretch' }} />
      </View>
    </Modal>
  );
}
