// Settings → Security: the phone's own check of its owner when Ting ID opens
// and before payments. Turning either off asks for the check itself, so a
// phone left unlocked cannot just switch it off.
import React, { useEffect, useState } from 'react';
import { Platform, Switch, View } from 'react-native';
import { checkOwner, ownerCheckAvailable } from '@/state/ownerCheck';
import { useStore } from '@/state/store';
import { Banner, Caption, Card, Divider, Row, SectionHeader, Sub } from './components';
import { sentence } from './describe';
import { useTheme } from './theme';

const INFO =
  'The phone checks it is you with your fingerprint, face, or its screen lock’s PIN, pattern or password. Ting ID never sees them: the phone only says yes or no.';

type Key = 'unlockApp' | 'confirmPayments';

export function SecuritySettings() {
  const t = useTheme();
  const settings = useStore((s) => s.settings);
  const setSettings = useStore((s) => s.setSettings);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    ownerCheckAvailable().then(setAvailable, () => setAvailable(false));
  }, []);

  const set = async (key: Key, value: boolean, prompt: string) => {
    setProblem(null);
    if (!value) {
      const r = await checkOwner(prompt);
      if (r === 'cancelled') return;
      if (r === 'failed') return setProblem('the phone did not confirm it is you');
    }
    setSettings(key === 'unlockApp' ? { unlockApp: value } : { confirmPayments: value });
  };

  const row = (key: Key, title: string, hint: string, prompt: string) => (
    <Row style={{ justifyContent: 'space-between' }}>
      <View style={{ flex: 1, gap: 2 }}>
        <Sub>{title}</Sub>
        <Caption>{hint}</Caption>
      </View>
      <Switch accessibilityLabel={title} value={settings[key] !== false} onValueChange={(v) => set(key, v, prompt)} trackColor={{ true: t.primary, false: t.border }} />
    </Row>
  );

  return (
    <>
      <SectionHeader title="Security" info={INFO} />
      <Card>
        {available === false ? (
          <Caption>
            {Platform.OS === 'web'
              ? 'The browser build has no fingerprint or screen lock to ask.'
              : 'This phone has no screen lock. Set a PIN, pattern, password or fingerprint in the phone’s settings, and Ting ID asks for it when it opens and before payments.'}
          </Caption>
        ) : (
          <>
            {row('unlockApp', 'Unlock Ting ID', 'When it opens, and after a minute away', 'Turn off unlocking Ting ID')}
            <Divider />
            {row('confirmPayments', 'Confirm payments and changes', 'Before a payment, an identity change, a recovery approval, minting or signing for a site', 'Turn off confirming payments and changes')}
          </>
        )}
        {problem ? <Banner tone="danger">{sentence(problem)}</Banner> : null}
      </Card>
    </>
  );
}
