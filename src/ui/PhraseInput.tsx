// Typing a recovery phrase: one numbered field per word, the list's words
// offered for the one being typed, and a pasted phrase spread over the
// fields (phraseEntry.ts). Keyboards are told not to learn or suggest the
// words: they are a secret.
import React, { Fragment, useRef, useState } from 'react';
import { Keyboard, Platform, Pressable, Text, TextInput, useWindowDimensions, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { PHRASE_LENGTHS, suggestWords } from '@/chain/phrase';
import { Button, Caption, ListItem, Row, Sheet, Sub } from './components';
import { sentence } from './describe';
import { enterWords, entryProblem, finishedWord, isPhraseWord, phraseFields } from './phraseEntry';
import { font, radius, spacing, type, useTheme } from './theme';

export function PhraseInput({ words, onChange }: { words: string[]; onChange: (words: string[]) => void }) {
  const t = useTheme();
  const { width } = useWindowDimensions();
  const columns = width >= 600 ? 3 : 2;
  const inputs = useRef<(TextInput | null)[]>([]);
  const [focused, setFocused] = useState<number | null>(null);
  const [lengths, setLengths] = useState(false);

  const focus = (i: number) => (i < words.length ? inputs.current[i]?.focus() : Keyboard.dismiss());

  const enter = (i: number, raw: string) => {
    const { words: next, move } = enterWords(words, i, raw);
    onChange(next);
    if (move === 'close') Keyboard.dismiss();
    else if (move === 'next') focus(i + 1);
  };

  const pick = (i: number, word: string) => {
    onChange(words.map((w, j) => (j === i ? word : w)));
    focus(i + 1);
  };

  const filled = words.filter(Boolean).length;
  const problem = entryProblem(words, focused);
  const typing = focused !== null ? words[focused] : '';
  const offers = typing && !finishedWord(typing) ? suggestWords(typing) : [];
  const rows = Array.from({ length: Math.ceil(words.length / columns) }, (_, r) => words.slice(r * columns, (r + 1) * columns).map((_, c) => r * columns + c));

  return (
    <>
      <Row style={{ justifyContent: 'space-between' }}>
        <Sub>Recovery phrase</Sub>
        <Row gap={spacing.md}>
          {filled > 0 ? <Button title="Clear" kind="ghost" small onPress={() => onChange(phraseFields(words.length))} /> : null}
          <Pressable onPress={() => setLengths(true)} hitSlop={8} accessibilityRole="button" accessibilityLabel={`${words.length} words, change`} style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
            <Text style={{ color: t.primary, fontSize: 13.5, ...font.bold }}>{words.length} words</Text>
            <Ionicons name="chevron-down" size={14} color={t.primary} />
          </Pressable>
        </Row>
      </Row>
      <View style={{ gap: spacing.sm }}>
        {rows.map((row, r) => (
          <Fragment key={r}>
            <View style={{ flexDirection: 'row', gap: spacing.sm }}>
              {row.map((i) => {
                const word = words[i];
                const bad = !!word && (i === focused ? suggestWords(word, 1).length === 0 : !isPhraseWord(word));
                return (
                  <View
                    key={i}
                    style={{
                      flex: 1,
                      flexDirection: 'row',
                      alignItems: 'center',
                      borderRadius: radius.md,
                      borderWidth: 1,
                      borderColor: bad ? t.danger : i === focused ? t.primary : t.border,
                      backgroundColor: t.cardAlt,
                      paddingLeft: 10,
                    }}>
                    <Text style={[{ color: t.muted, fontSize: 12, minWidth: 18, textAlign: 'right', ...font.semibold }]}>{i + 1}</Text>
                    <TextInput
                      ref={(el) => {
                        inputs.current[i] = el;
                      }}
                      value={word}
                      onChangeText={(v) => enter(i, v)}
                      onFocus={() => setFocused(i)}
                      onBlur={() => setFocused((f) => (f === i ? null : f))}
                      onSubmitEditing={() => focus(i + 1)}
                      submitBehavior="submit"
                      returnKeyType={i === words.length - 1 ? 'done' : 'next'}
                      accessibilityLabel={`Word ${i + 1}`}
                      autoCapitalize="none"
                      autoCorrect={false}
                      autoComplete="off"
                      importantForAutofill="no"
                      spellCheck={false}
                      keyboardType={Platform.OS === 'android' ? 'visible-password' : 'default'}
                      style={{
                        flex: 1,
                        minWidth: 0,
                        color: t.text,
                        fontSize: 16,
                        ...font.semibold,
                        paddingHorizontal: 8,
                        paddingVertical: Platform.OS === 'web' ? 11 : 9,
                      }}
                    />
                  </View>
                );
              })}
              {/* The last row keeps the columns' width. */}
              {row.length < columns ? Array.from({ length: columns - row.length }, (_, k) => <View key={`gap-${k}`} style={{ flex: 1 }} />) : null}
            </View>
            {focused !== null && row.includes(focused) && typing ? (
              offers.length > 0 ? (
                <Row gap={6} style={{ flexWrap: 'wrap' }}>
                  {offers.map((w) => (
                    <Button key={w} title={w} kind="secondary" small onPress={() => pick(focused, w)} />
                  ))}
                </Row>
              ) : (
                <Text style={[type.caption, { color: t.danger }]}>No recovery phrase word starts with these letters.</Text>
              )
            ) : null}
          </Fragment>
        ))}
      </View>
      {problem ? (
        <Text style={[type.caption, { color: t.danger }]}>{sentence(problem)}</Text>
      ) : (
        <Caption>
          {filled} of {words.length} words
        </Caption>
      )}

      <Sheet visible={lengths} onClose={() => setLengths(false)} title="Number of words">
        <Caption>Ting ID makes phrases of 12 words. Phrases of up to 24 words work too.</Caption>
        {PHRASE_LENGTHS.map((n) => (
          <ListItem
            key={n}
            title={`${n} words`}
            selected={n === words.length}
            right={n === words.length ? <Ionicons name="checkmark" size={18} color={t.primary} /> : null}
            onPress={() => {
              onChange(phraseFields(n, words));
              setLengths(false);
            }}
          />
        ))}
      </Sheet>
    </>
  );
}
