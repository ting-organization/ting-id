// Which users a device key serves: a key is only offered for deletion when no
// user, and no identity on chain, may still sign with it.
import { describe, expect, it, vi } from 'vitest';
import { hex } from '../src/chain/bytes';
import { canonical, policyFor } from '../src/chain/contracts';
import * as idp from '../src/chain/identity';
import type { IdentityInfo } from '../src/state/chain';
import { keyUsers } from '../src/state/registry';
import type { KeyMeta, User } from '../src/state/types';

vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));
vi.mock('expo-crypto', () => ({ getRandomBytes: (n: number) => new Uint8Array(n), randomUUID: () => 'id' }));
vi.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: { getItem: async () => null, setItem: async () => {}, removeItem: async () => {}, getAllKeys: async () => [], multiRemove: async () => {} },
}));

const c = canonical(new Uint8Array(32).fill(7));
const pub = new Uint8Array(65).fill(4);
const key: KeyMeta = { id: 'k', name: 'Backup', scheme: 'secp256k1', pub: hex(pub), createdAt: 0 };
const listed = { keyId: 3n, key: { policy: policyFor(c, 'secp256k1'), params: pub } };
const identity = (keys: IdentityInfo['keys']) => ({ keys }) as IdentityInfo;
const alice: User = { id: 'a', name: 'Alice', color: '', kind: 'identity', identityId: '11'.repeat(32), registry: '22'.repeat(32), keyId: 'other' };

describe('key users', () => {
  it('count the user a key was made for', () => {
    const simple: User = { id: 's', name: 'Savings', color: '', kind: 'key', keyId: 'k' };
    expect(keyUsers(key, [simple], new Map(), c)).toEqual({ used: ['Savings'], unknown: [] });
  });

  it('count an identity that lists the key on chain, though the key was not made for it', () => {
    expect(keyUsers(key, [alice], new Map([['a', identity([listed])]]), c)).toEqual({ used: ['Alice'], unknown: [] });
    expect(keyUsers(key, [alice], new Map([['a', identity([])]]), c)).toEqual({ used: [], unknown: [] });
  });

  it('count an identity not on chain yet by its creation', () => {
    const creation = idp.encodeCreation({ keys: [listed.key], guardians: [], threshold: 0, changeDelay: 0, recoveryDelay: 0, slots: [0], salt: new Uint8Array(32) });
    expect(keyUsers(key, [{ ...alice, creation: hex(creation) }], new Map([['a', null]]), c)).toEqual({ used: ['Alice'], unknown: [] });
  });

  it('leave a key undecided while an identity is not read, or no chain answered', () => {
    expect(keyUsers(key, [alice], new Map(), c)).toEqual({ used: [], unknown: ['Alice'] });
    expect(keyUsers(key, [alice], new Map([['a', identity([listed])]]), null)).toEqual({ used: [], unknown: ['Alice'] });
  });
});
