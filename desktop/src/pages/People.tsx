import { useState } from 'react';
import { useChainCache } from '@/core/state/cache';
import { isHex } from '@/core/chain/bytes';
import { shortId } from '@/core/chain/format';
import { uuid } from '@/core/state/keystore';
import { useActiveUser, useStore } from '@/core/state/store';
import { Avatar, Button, Caption, Dialog, Field, ListItem, Page, Panel, Pill, Row } from '@/ui/components';
import { useConfirm } from '@/ui/confirm';
import { useModal } from '@/ui/modal';

export function PeoplePage() {
  const modal = useModal();
  const users = useStore((s) => s.users);
  const contacts = useStore((s) => s.contacts);
  const chain = useChainCache()?.chain ?? null;
  const active = useActiveUser();
  const setActive = useStore((s) => s.setActiveUser);
  const removeUser = useStore((s) => s.removeUser);
  const addContact = useStore((s) => s.addContact);
  const removeContact = useStore((s) => s.removeContact);
  const confirm = useConfirm();
  const [contactOpen, setContactOpen] = useState(false);
  const [contactName, setContactName] = useState('');
  const [contactAccount, setContactAccount] = useState('');

  return (
    <Page title="People">
      <div className="grid-2">
        <div className="col">
          <Panel
            title="Users on this computer"
            action="Add"
            onAction={() => modal.open('/user/new')}
            info="Each user is an identity, or with the advanced settings on, a simple account of one key. Removing one here only forgets it on this computer; its funds stay on chain."
          >
            {users.length === 0 ? (
              <div style={{ padding: 16 }}>
                <Caption>No users yet.</Caption>
              </div>
            ) : null}
            {users.map((u) => (
              <ListItem
                key={u.id}
                left={<Avatar name={u.name} color={u.color} />}
                title={u.name}
                subtitle={u.kind === 'identity' ? `identity ${shortId(u.identityId ?? '', 8, 6)}` : 'simple account'}
                selected={u.id === active?.id}
                onClick={() => setActive(u.id)}
                right={
                  <Row>
                    {u.id === active?.id ? <Pill label="active" tone="primary" small /> : null}
                    <span onClick={(e) => e.stopPropagation()}>
                      <Button
                        title="Remove…"
                        kind="ghost"
                        small
                        onClick={async () => {
                          const yes = await confirm({
                            title: `Remove ${u.name} from this computer?`,
                            body:
                          u.kind === 'key'
                            ? 'Its funds stay on chain, and only its key can spend them. The key stays on this computer: with the advanced settings on, Settings → Keys can add it as a user again.'
                            : 'Its funds stay on chain. This computer’s key for it stays: adding the identity again as an existing one uses it.',
                            confirm: 'Remove',
                            back: 'Keep',
                            danger: true,
                          });
                          if (yes) removeUser(u.id);
                        }}
                      />
                    </span>
                  </Row>
                }
              />
            ))}
          </Panel>
        </div>
        <div className="col">
          <Panel title="Contacts" action="Add" onAction={() => setContactOpen(true)} info="Accounts you send to often, by name.">
            {contacts.length === 0 ? (
              <div style={{ padding: 16 }}>
                <Caption>No contacts yet.</Caption>
              </div>
            ) : null}
            {contacts.map((c) => (
              <ListItem
                key={c.id}
                left={<Avatar name={c.name} color="#64748B" />}
                title={c.name}
                subtitle={`${shortId(c.account, 10, 8)}${c.chain && c.chain !== chain ? ' · another network' : ''}`}
                right={
                  <Button
                    title="Remove…"
                    kind="ghost"
                    small
                    onClick={async () => {
                      if (await confirm({ title: `Remove contact ${c.name}?`, confirm: 'Remove', back: 'Keep', danger: true })) removeContact(c.id);
                    }}
                  />
                }
              />
            ))}
          </Panel>
        </div>
      </div>

      <Dialog open={contactOpen} onClose={() => setContactOpen(false)} title="New contact">
        <Field label="Name" value={contactName} onChangeText={setContactName} placeholder="Bob" autoFocus />
        <Field label="Account" value={contactAccount} onChangeText={setContactAccount} placeholder="64 hex digits" mono />
        <Button
          title="Save"
          disabled={!contactName.trim() || !isHex(contactAccount, 32)}
          onClick={() => {
            addContact({ id: uuid(), name: contactName.trim(), account: contactAccount.trim().toLowerCase() });
            setContactName('');
            setContactAccount('');
            setContactOpen(false);
          }}
        />
      </Dialog>
    </Page>
  );
}
