// Names this device knows for accounts and identities.
import { useMemo } from 'react';
import { hex, unhex } from '@/chain/bytes';
import { policyAccount } from '@/chain/accounts';
import { Canonical, policyFor } from '@/chain/contracts';
import * as idp from '@/chain/identity';
import { slotLabel } from './chain';
import { useContactsHere, useStore } from './store';
import type { Names } from '@/ui/describe';

export function useNames(c: Canonical | null): Names {
  const users = useStore((s) => s.users);
  const keys = useStore((s) => s.keys);
  const contacts = useContactsHere();
  return useMemo(() => {
    const names: Names = new Map();
    for (const contact of contacts) names.set(contact.account.toLowerCase(), contact.name);
    for (const user of users) {
      if (user.kind === 'identity' && user.identityId && user.registry) {
        names.set(user.identityId.toLowerCase(), user.name);
        const slots = user.creation ? idp.decodeCreation(unhex(user.creation))?.slots : null;
        const count = Math.max(slots?.length ?? 0, 2);
        for (let slot = 0; slot < Math.max(count, 4); slot++) {
          const account = idp.account(unhex(user.registry), unhex(user.identityId), slot);
          const label = slotLabel(slot);
          names.set(hex(account), `${user.name} · ${label}`);
        }
      } else if (user.kind === 'key' && c) {
        const key = keys.find((k) => k.id === user.keyId);
        if (key) names.set(hex(policyAccount(policyFor(c, key.scheme), unhex(key.pub))), user.name);
      }
    }
    return names;
  }, [users, keys, contacts, c]);
}
