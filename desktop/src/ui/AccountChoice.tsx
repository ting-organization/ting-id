// Picking one of the user's accounts, each with what its lock means.
import type { UserAccount } from '@/core/state/chain';
import { relativeDuration } from '@/core/describe';
import { IconCircle, ListItem, Pill } from './components';

export function AccountChoice({
  accounts,
  slot,
  onSlot,
  note,
  badge,
}: {
  accounts: UserAccount[];
  slot: number | null;
  onSlot: (slot: number) => void;
  /** What choosing the account means, in place of its lock. */
  note?: (a: UserAccount) => string;
  /** A short label to show on one account, such as "recommended". */
  badge?: { slot: number; label: string };
}) {
  return (
    <div className="card flush">
      {accounts.map((a, i) => {
        const locked = a.delay > 0;
        return (
          <ListItem
            key={a.slot}
            first={i === 0}
            left={<IconCircle name={locked ? 'lock-closed-outline' : 'wallet-outline'} tone={locked ? 'accent' : 'primary'} size={32} />}
            title={a.label}
            subtitle={note ? note(a) : locked ? `withdrawals wait ${relativeDuration(a.delay)}` : 'instant'}
            selected={a.slot === slot}
            right={badge?.slot === a.slot ? <Pill label={badge.label} tone="success" small /> : null}
            onClick={() => onSlot(a.slot)}
          />
        );
      })}
    </div>
  );
}
