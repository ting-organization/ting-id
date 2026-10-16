// The window: a sidebar with the sections, the node status and the active
// user; the page on the right.
import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { hex } from '@/core/chain/bytes';
import { shortId } from '@/core/chain/format';
import { useChainInfo } from '@/core/state/chain';
import { useActiveUser } from '@/core/state/store';
import { Avatar, Caption } from './components';
import { Icon } from './icons';
import { ProfileDialog, userKindLabel } from './Profile';

/** The networks the app knows by name, by ChainId. */
const NETWORKS: Record<string, string> = {
  '26520bbe40340aabafdc0da89c128e4849146d4b3e59edabd7899d6782a6e653': 'Production',
  '8578787146be1ef0aacc797754001290272915e89cd9e5784fe1368e4c2131bc': 'Development',
};

const NAV = [
  { to: '/', label: 'Wallet', icon: 'wallet-outline' },
  { to: '/send', label: 'Send', icon: 'paper-plane-outline' },
  { to: '/coins', label: 'Coins', icon: 'layers-outline' },
  { to: '/identity', label: 'Identity', icon: 'finger-print-outline' },
  { to: '/people', label: 'People', icon: 'people-outline' },
  { to: '/settings', label: 'Settings', icon: 'settings-outline' },
];

export function Layout() {
  const { info, error } = useChainInfo();
  const user = useActiveUser();
  const [profile, setProfile] = useState(false);
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <img className="brand-mark" src="/logo.svg" alt="" />
          Ting ID
        </div>
        {NAV.map((n) => (
          <NavLink key={n.to} to={n.to} end={n.to === '/'} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
            <Icon name={n.icon} size={18} />
            {n.label}
          </NavLink>
        ))}
        <div className="sidebar-spacer" />
        <div className="node-pill" title={error ?? info?.status.name ?? ''}>
          <span className={`dot${error ? ' off' : ''}`} />
          {error ? 'Offline' : info ? (NETWORKS[hex(info.status.chainId)] ?? `Chain ${hex(info.status.chainId).slice(0, 8)}`) : 'Connecting'}
          {info || !error ? (
            <>
              {' '}
              · block <span className="num">{info?.status.height ?? '…'}</span>
            </>
          ) : null}
        </div>
        <button className="user-card" onClick={() => setProfile(true)}>
          {user ? (
            <>
              <Avatar name={user.name} color={user.color} size={28} />
              <span className="grow" style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{user.name}</span>
                <Caption>{user.kind === 'identity' && user.identityId ? <span className="num">{shortId(user.identityId, 6, 4)}</span> : userKindLabel(user.kind)}</Caption>
              </span>
            </>
          ) : (
            <>
              <span className="avatar" style={{ width: 32, height: 32, background: 'var(--card-alt)', color: 'var(--muted)' }}>
                <Icon name="person-outline" size={18} />
              </span>
              <span className="grow" style={{ fontWeight: 600 }}>
                No user yet
              </span>
            </>
          )}
          <Icon name="chevron-up" size={16} color="var(--faint)" />
        </button>
      </aside>
      <main className="main">
        <Outlet />
      </main>
      <ProfileDialog open={profile} onClose={() => setProfile(false)} />
    </div>
  );
}
