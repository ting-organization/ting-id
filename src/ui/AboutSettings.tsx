// Settings → About: the version this phone runs, and, when asked, whether a
// newer release is out on GitHub. Ting ID does not update itself, so this is
// how a security release is found; GitHub is asked only on the tap, and sees
// this phone's address when it is.
import React, { useState } from 'react';
import { Linking } from 'react-native';
import Constants from 'expo-constants';
import { Button, Caption, Card, Row, SectionHeader } from './components';
import { sentence } from './describe';
import { newer } from './versions';

const RELEASES = 'https://api.github.com/repos/ting-organization/ting-id/releases/latest';

export function AboutSettings() {
  const mine = Constants.expoConfig?.version ?? 'unknown';
  const [state, setState] = useState<{ kind: 'idle' | 'asking' } | { kind: 'newest' } | { kind: 'newer'; version: string; url: string } | { kind: 'failed'; message: string }>({
    kind: 'idle',
  });
  const check = async () => {
    setState({ kind: 'asking' });
    try {
      const res = await fetch(RELEASES, { credentials: 'omit', headers: { accept: 'application/vnd.github+json' } });
      if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
      const release = (await res.json()) as { tag_name?: unknown; html_url?: unknown };
      const tag = typeof release.tag_name === 'string' ? release.tag_name : '';
      const url = typeof release.html_url === 'string' && release.html_url.startsWith('https://github.com/ting-organization/ting-id/') ? release.html_url : null;
      setState(newer(tag, mine) && url ? { kind: 'newer', version: tag.replace(/^v/, ''), url } : { kind: 'newest' });
    } catch (e) {
      setState({ kind: 'failed', message: (e as Error).message || 'GitHub could not be reached' });
    }
  };
  return (
    <>
      <SectionHeader title="About" />
      <Card>
        <Row style={{ justifyContent: 'space-between' }}>
          <Caption>Ting ID {mine}</Caption>
          <Button title="Check for updates" kind="ghost" small icon="cloud-download-outline" loading={state.kind === 'asking'} onPress={check} />
        </Row>
        {state.kind === 'newest' ? <Caption>This is the newest release.</Caption> : null}
        {state.kind === 'newer' ? (
          <>
            <Caption>Ting ID {state.version} is out. Updates can carry security fixes: install it over this one, and your users and keys stay.</Caption>
            <Button title={`Get Ting ID ${state.version}`} kind="secondary" small icon="open-outline" onPress={() => Linking.openURL(state.url).catch(() => {})} />
          </>
        ) : null}
        {state.kind === 'failed' ? <Caption>{sentence(state.message)}</Caption> : null}
        <Caption>Checking asks GitHub, which then sees this phone’s address. Nothing is checked unless you tap.</Caption>
      </Card>
    </>
  );
}
