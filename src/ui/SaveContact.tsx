// Naming an account that was pasted or scanned: it joins the contacts, and
// the wallet shows the name wherever the account appears.
import React, { useState } from 'react';
import { uuid } from '@/state/keystore';
import { useStore } from '@/state/store';
import { Button, Caption, Field, Sheet } from './components';
import { type } from './theme';

export function SaveContactSheet({ account, visible, onClose }: { account: string; visible: boolean; onClose: () => void }) {
  const addContact = useStore((s) => s.addContact);
  const [name, setName] = useState('');
  const save = () => {
    addContact({ id: uuid(), name: name.trim(), account: account.toLowerCase() });
    setName('');
    onClose();
  };
  return (
    <Sheet visible={visible} onClose={onClose} title="Save as contact">
      <Caption style={type.mono}>{account}</Caption>
      <Field label="Name" value={name} onChangeText={setName} placeholder="Bob" autoFocus returnKeyType="done" onSubmitEditing={() => name.trim() && save()} />
      <Button title="Save contact" disabled={!name.trim()} onPress={save} />
    </Sheet>
  );
}
