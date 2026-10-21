// Naming an account that was pasted or scanned: it joins the contacts, and
// the wallet shows the name wherever the account appears.
import React, { useState } from 'react';
import { uuid } from '@/state/keystore';
import { useStore } from '@/state/store';
import { Button, Caption, Field, Sheet } from './components';
import { type } from './theme';

export function SaveContactSheet({ account, visible, onClose }: { account: string; visible: boolean; onClose: () => void }) {
  const addContact = useStore((s) => s.addContact);
  const contacts = useStore((s) => s.contacts);
  const [name, setName] = useState('');
  const taken = !!name.trim() && contacts.some((x) => x.name.trim().toLowerCase() === name.trim().toLowerCase());
  const save = () => {
    addContact({ id: uuid(), name: name.trim(), account: account.toLowerCase() });
    setName('');
    onClose();
  };
  return (
    <Sheet visible={visible} onClose={onClose} title="Save as contact">
      <Caption style={type.mono}>{account}</Caption>
      <Field
        label="Name"
        value={name}
        onChangeText={setName}
        placeholder="Bob"
        autoFocus
        returnKeyType="done"
        onSubmitEditing={() => name.trim() && !taken && save()}
        error={taken ? 'A contact is called that already.' : null}
      />
      <Button title="Save contact" disabled={!name.trim() || taken} onPress={save} />
    </Sheet>
  );
}
