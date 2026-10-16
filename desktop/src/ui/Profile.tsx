// The profile dialog behind the user card: the active user, the other users
// on this computer and the way to add one.
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { shortId } from '@/core/chain/format';
import { useActiveUser, useStore } from '@/core/state/store';
import { Avatar, Button, Caption, Dialog, IdChip, ListItem, Pill, Row } from './components';
import { Icon } from './icons';
import { useModal } from './modal';

export function userKindLabel(kind: 'identity' | 'key'): string {
  return kind === 'identity' ? 'Identity' : 'Simple account';
}

export function ProfileDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const modal = useModal();
  const users = useStore((s) => s.users);
  const setActive = useStore((s) => s.setActiveUser);
  const active = useActiveUser();
  const [switching, setSwitching] = useState(false);
  const close = () => {
    setSwitching(false);
    onClose();
  };
  const others = users.filter((u) => u.id !== active?.id);
  return (
    <Dialog open={open} onClose={close}>
      {active ? (
        <Row gap={14}>
          <Avatar name={active.name} color={active.color} size={56} />
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="heading">{active.name}</span>
            <span>
              <Pill label={userKindLabel(active.kind)} tone="primary" small />
            </span>
          </div>
        </Row>
      ) : (
        <Caption>No user on this computer yet.</Caption>
      )}
      {active?.kind === 'identity' && active.identityId ? (
        <div>
          <IdChip label="ID" value={active.identityId} />
        </div>
      ) : null}
      <Row>
        <Button
          title="Identity"
          kind="secondary"
          small
          icon="finger-print-outline"
          onClick={() => {
            close();
            navigate('/identity');
          }}
        />
        <Button
          title="Settings"
          kind="ghost"
          small
          icon="settings-outline"
          onClick={() => {
            close();
            navigate('/settings');
          }}
        />
      </Row>
      {others.length > 0 || switching ? (
        <div style={{ background: 'var(--card-alt)', borderRadius: 'var(--radius-md)' }}>
          <button className="list-item clickable first" onClick={() => setSwitching((s) => !s)} style={{ borderRadius: 'var(--radius-md)' }}>
            <Caption className="grow">
              Switch user ({others.length} other{others.length === 1 ? '' : 's'})
            </Caption>
            <Icon name={switching ? 'chevron-up' : 'chevron-down'} size={16} color="var(--muted)" />
          </button>
          {switching
            ? others.map((u) => (
                <ListItem
                  key={u.id}
                  left={<Avatar name={u.name} color={u.color} size={34} />}
                  title={u.name}
                  subtitle={u.kind === 'identity' ? `identity ${shortId(u.identityId ?? '', 6, 4)}` : 'simple account'}
                  onClick={() => {
                    setActive(u.id);
                    close();
                  }}
                />
              ))
            : null}
        </div>
      ) : null}
      <Button
        title="Add a user"
        kind="ghost"
        small
        icon="person-add-outline"
        onClick={() => {
          close();
          modal.open('/user/new');
        }}
      />
    </Dialog>
  );
}
