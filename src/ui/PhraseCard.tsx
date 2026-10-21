// The recovery phrase of the active user, near the end of Settings: whether
// the words restore it, a reminder to write them down, and making one for an
// identity that has none, or whose phrase key a recovery or change replaced.
import React, { useState } from 'react';
import { useRouter } from 'expo-router';
import { equal, unhex } from '@/chain/bytes';
import { formatTime } from '@/chain/format';
import type { IdentityInfo } from '@/state/chain';
import { newUserPhrase } from '@/state/keys';
import { linkedKeyId } from '@/state/registry';
import { useStore } from '@/state/store';
import type { KeyMeta, User } from '@/state/types';
import type { Canonical } from '@/chain/contracts';
import { Banner, Button, Caption, Card, SectionHeader } from './components';
import { sentence } from './describe';

const INFO =
  'Twelve words that restore this identity on a new phone. Your guardians can restore it too, and replace the words if someone else got hold of them.';

export function PhraseCard({
  user,
  identity,
  loading,
  c,
  keys,
}: {
  user: User;
  identity: IdentityInfo | null;
  loading?: boolean;
  c: Canonical | null;
  keys: KeyMeta[];
}) {
  const router = useRouter();
  // With several users, whose words these are.
  const several = useStore((s) => s.users.length > 1);
  const title = several ? `${user.name}’s recovery phrase` : 'Recovery phrase';
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = keys.find((k) => k.id === user.phraseKeyId) ?? null;
  const show = () => router.push({ pathname: '/phrase/[id]', params: { id: user.id } });

  // A new phrase: its words to write down first, then the change that adds its key.
  const updateUser = useStore((s) => s.updateUser);
  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      await newUserPhrase(user);
      updateUser(user.id, { phraseToAdd: true });
      router.push({ pathname: '/phrase/[id]', params: { id: user.id, next: 'add' } });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  // A bare key account has no phrase: its key is the account.
  if (user.kind === 'key') {
    return (
      <>
        <SectionHeader title={title} />
        <Card>
          <Caption>This account has no recovery phrase: its key exists only on this phone.</Caption>
        </Card>
      </>
    );
  }

  const listed = !!identity && !!c && !!key && linkedKeyId(c, identity, key) !== null;
  // A change adding the phrase's key that has not applied yet.
  const pending =
    identity && key
      ? identity.pending.find((p) => p.record.operations.some((op) => op.kind === 'addKey' && equal(op.key.params, unhex(key.pub))))
      : undefined;

  return (
    <>
      <SectionHeader title={title} info={INFO} />
      <Card>
        {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
        {user.phrase && listed ? (
          <>
            <Caption>{user.phraseBackedUp ? 'The words restore this identity on a new phone.' : 'Write the words down, so they can restore this identity.'}</Caption>
            <Button title={user.phraseBackedUp ? 'Show recovery phrase' : 'Write it down'} kind={user.phraseBackedUp ? 'secondary' : 'primary'} icon="document-lock-outline" onPress={show} />
          </>
        ) : user.phrase && pending ? (
          <>
            <Caption>The words restore this identity once the change adding their key applies, {formatTime(pending.record.effectiveAt)}.</Caption>
            <Button title={user.phraseBackedUp ? 'Show recovery phrase' : 'Write it down'} kind="secondary" icon="document-lock-outline" onPress={show} />
          </>
        ) : user.phrase && identity && user.phraseToAdd && key ? (
          // New words whose change was never proposed, as when the screen was left before.
          <>
            <Caption>Your new words are kept on this phone, but their key is not added to the identity yet, so they do not restore it.</Caption>
            <Button title="Add their key" kind="primary" icon="add" onPress={() => router.push({ pathname: '/identity/propose', params: { kind: 'addKey', key: key.id } })} />
          </>
        ) : user.phrase && identity ? (
          <>
            <Caption>The words on this phone no longer restore this identity: a recovery or a change replaced their key.</Caption>
            <Button title="Make a new recovery phrase" kind="secondary" icon="add" onPress={add} loading={busy} />
          </>
        ) : identity ? (
          <>
            <Caption>
              {identity.core.guardianCount > 0n
                ? 'No recovery phrase on this phone. Your guardians can still restore the identity.'
                : 'No recovery phrase on this phone, and no guardians: if this phone is lost, so is the identity. Add a recovery phrase.'}
            </Caption>
            <Button title="Add a recovery phrase" kind={identity.core.guardianCount > 0n ? 'secondary' : 'primary'} icon="add" onPress={add} loading={busy} />
          </>
        ) : loading ? (
          <Caption>Reading the identity…</Caption>
        ) : user.phrase ? (
          // Not on the chain yet, or no node answers: the words are on this phone all the same.
          <>
            <Caption>The words restore this identity once a block includes it.</Caption>
            <Button title={user.phraseBackedUp ? 'Show recovery phrase' : 'Write it down'} kind="secondary" icon="document-lock-outline" onPress={show} />
          </>
        ) : (
          <Caption>No recovery phrase on this phone.</Caption>
        )}
      </Card>
    </>
  );
}
