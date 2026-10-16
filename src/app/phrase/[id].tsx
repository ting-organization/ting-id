// A user's recovery phrase: shown after the phone confirms its owner, or
// right after it was made, with screenshots blocked. Writing it down is
// confirmed by typing three of the words back; until then the wallet keeps
// reminding.
import React, { useEffect, useState } from 'react';
import { Platform, Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { phraseWords } from '@/chain/phrase';
import { forgetFresh, freshWords, phoneHasLock, readPhrase } from '@/state/phrases';
import { useStore } from '@/state/store';
import { Banner, Button, Caption, Card, Field, Screen, Sub } from '@/ui/components';
import { sentence } from '@/ui/describe';
import { font, radius, spacing, type, useTheme } from '@/ui/theme';

/** Blocks screenshots while the words show; the browser build, for development, has no such thing. */
const usePreventCapture = Platform.OS === 'web' ? () => {} : usePreventScreenCapture;

/** Words asked back to confirm the phrase was written down. */
const CHECKS = 3;

export default function PhraseScreen() {
  usePreventCapture();
  const t = useTheme();
  const router = useRouter();
  // `next=add`: a new phrase for an identity, whose key a change then adds.
  const { id = '', next } = useLocalSearchParams<{ id: string; next?: string }>();
  const user = useStore((s) => s.users.find((u) => u.id === id) ?? null);
  const updateUser = useStore((s) => s.updateUser);
  const [words, setWords] = useState<string[] | null>(() => {
    const f = freshWords(id);
    return f ? phraseWords(f) : null;
  });
  // The places asked back, picked when checking starts; none while the words show.
  const [places, setPlaces] = useState<number[] | null>(null);
  const [answers, setAnswers] = useState<string[]>(Array(CHECKS).fill(''));
  const [unlocked, setUnlocked] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    phoneHasLock()
      .then(setUnlocked)
      .catch(() => setUnlocked(null));
  }, []);

  const leave = () => {
    forgetFresh(id);
    const keyId = useStore.getState().users.find((u) => u.id === id)?.phraseKeyId;
    if (next === 'add' && keyId) router.replace({ pathname: '/identity/propose', params: { kind: 'addKey', key: keyId } });
    else if (router.canGoBack()) router.back();
    else router.replace('/');
  };

  if (!user || !user.phrase) {
    return (
      <Screen belowHeader>
        <Banner tone="warning">This phone keeps no recovery phrase for this user.</Banner>
        <Button title="Back" kind="secondary" onPress={leave} />
      </Screen>
    );
  }

  const show = async () => {
    setBusy(true);
    setError(null);
    try {
      const stored = await readPhrase(user);
      if (!stored) throw new Error('this phone keeps no recovery phrase for this user');
      setWords(phraseWords(stored));
    } catch (e) {
      if ((e as Error).message !== 'cancelled') setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** Three different places, in order. */
  const startCheck = () => {
    const all = Array.from({ length: words?.length ?? 12 }, (_, i) => i);
    for (let i = all.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [all[i], all[j]] = [all[j], all[i]];
    }
    setAnswers(Array(CHECKS).fill(''));
    setPlaces(all.slice(0, CHECKS).sort((a, b) => a - b));
  };

  const check = () => {
    if (!words || !places) return;
    const right = places.every((place, i) => answers[i].trim().toLowerCase() === words[place]);
    if (!right) return setError('one of the words does not match: look at your copy again');
    setError(null);
    updateUser(user.id, { phraseBackedUp: true });
    leave();
  };

  return (
    <Screen belowHeader>
      {unlocked === false ? <Banner tone="warning">This phone has no screen lock: anyone holding it can open Ting ID and see these words.</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Card>
        <Sub>{user.name}’s recovery phrase</Sub>
        <Caption>
          Anyone with these words can act for {user.kind === 'identity' ? 'this identity' : 'this account'}. Write them on paper, in this order, and keep it
          somewhere safe. Never type them into a website, and never share a photo of them.
        </Caption>
      </Card>

      {!words ? (
        <Button title="Show the words" icon="eye-outline" large onPress={show} loading={busy} />
      ) : !places ? (
        <>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm }}>
            {words.map((w, i) => (
              <View
                key={i}
                style={{
                  width: '48%',
                  flexDirection: 'row',
                  alignItems: 'baseline',
                  gap: 8,
                  paddingHorizontal: 14,
                  paddingVertical: 10,
                  borderRadius: radius.md,
                  borderWidth: 1,
                  borderColor: t.border,
                  backgroundColor: t.card,
                }}>
                <Text style={{ color: t.faint, fontSize: 12, width: 20, textAlign: 'right', ...font.semibold }}>{i + 1}</Text>
                <Text style={[type.body, { color: t.text, ...font.bold }]}>{w}</Text>
              </View>
            ))}
          </View>
          {user.phraseBackedUp ? (
            <Button title="Done" kind="secondary" onPress={leave} />
          ) : (
            <>
              <Button title="I wrote them down" large onPress={startCheck} />
              <Button title="Later" kind="ghost" onPress={leave} />
            </>
          )}
        </>
      ) : (
        <Card>
          <Sub>Check your copy</Sub>
          <Caption>Type these words from what you wrote down.</Caption>
          {places.map((place, i) => (
            <Field
              key={place}
              label={`Word ${place + 1}`}
              value={answers[i]}
              onChangeText={(v) => setAnswers((a) => a.map((x, j) => (j === i ? v : x)))}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="off"
              spellCheck={false}
            />
          ))}
          <Button title="Check" onPress={check} disabled={answers.some((a) => !a.trim())} />
          <Button title="Show the words again" kind="ghost" onPress={() => setPlaces(null)} />
        </Card>
      )}
    </Screen>
  );
}
