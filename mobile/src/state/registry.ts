// Identity operations: proposing and cancelling changes, applying due ones,
// guardian approvals and recovery (wiki: programs/identity). The registry's
// operations pay nothing themselves; a `payer` adds a fee from one of the
// user's instant accounts.
import { equal, hex, unhex } from '@/chain/bytes';
import { Canonical, canonical, policyFor } from '@/chain/contracts';
import type { FeePayer } from '@/chain/fees';
import * as idp from '@/chain/identity';
import { Scheme } from '@/chain/keys';
import type { ChainApi } from '@/chain/node';
import { IdentityInfo, resolveSigner, submitRegistryOp, submitWithFee, useChainData } from './chain';
import { useCached } from './cache';
import type { KeyMeta, User } from './types';

export interface IdentitySigner {
  scheme: Scheme;
  secret: Uint8Array;
  keyId: bigint;
  keyName: string;
}

/** A device key that is one of the identity's current keys. */
export async function identitySigner(
  user: User,
  c: Canonical,
  keys: KeyMeta[],
  identity: IdentityInfo | null,
  preferredKeyId?: string,
): Promise<IdentitySigner> {
  const s = await resolveSigner(user, 0, c, keys, identity, preferredKeyId);
  if (s.keyId === undefined) throw new Error('not an identity');
  return { scheme: s.key.scheme, secret: s.secret, keyId: s.keyId, keyName: s.key.name };
}

export async function proposeChange(
  node: ChainApi,
  chain: Uint8Array,
  identity: IdentityInfo,
  signer: IdentitySigner,
  ops: idp.Operation[],
  payer: FeePayer | null = null,
): Promise<string> {
  const change = idp.encodeChange(ops);
  const nonce = identity.core.opNonce;
  return submitRegistryOp(
    node,
    chain,
    identity.registry,
    identity.id,
    signer,
    idp.proposeBody(nonce, change),
    (auth) => idp.proposeInput(identity.id, nonce, auth, change),
    payer,
  );
}

/** Cancels pending changes with one signature; a cancel carries no op nonce. */
export async function cancelChanges(
  node: ChainApi,
  chain: Uint8Array,
  identity: IdentityInfo,
  signer: IdentitySigner,
  changeNos: bigint[],
  payer: FeePayer | null = null,
): Promise<string> {
  const numbers = [...changeNos].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return submitRegistryOp(
    node,
    chain,
    identity.registry,
    identity.id,
    signer,
    idp.cancelBody(numbers),
    (auth) => idp.cancelInput(identity.id, auth, numbers),
    payer,
  );
}

export async function applyChange(node: ChainApi, chain: Uint8Array, identity: IdentityInfo, changeNo: bigint, payer: FeePayer | null = null): Promise<string> {
  return submitWithFee(node, chain, canonical(chain), { target: identity.registry, input: idp.applyInput(identity.id, changeNo) }, payer);
}

/**
 * A guardian approves replacing `target`'s keys with `keyList`. The input carries
 * the key list itself; the registry derives the proposal in `target`'s current
 * recovery epoch, which `proposal` must be, since that is what the key signs.
 */
export async function approveRecovery(
  node: ChainApi,
  chain: Uint8Array,
  registry: Uint8Array,
  guardian: IdentityInfo,
  guardianSigner: IdentitySigner,
  target: Uint8Array,
  keyList: Uint8Array,
  proposal: Uint8Array,
  payer: FeePayer | null = null,
): Promise<string> {
  return submitRegistryOp(
    node,
    chain,
    registry,
    target,
    guardianSigner,
    idp.approvalBody(idp.OP_APPROVE, proposal, guardian.id),
    (auth) => idp.approveInput(target, guardian.id, keyList, auth),
    payer,
  );
}

/** A guardian withdraws its approval of `proposal`. */
export async function withdrawApproval(
  node: ChainApi,
  chain: Uint8Array,
  registry: Uint8Array,
  guardian: IdentityInfo,
  guardianSigner: IdentitySigner,
  target: Uint8Array,
  proposal: Uint8Array,
  payer: FeePayer | null = null,
): Promise<string> {
  return submitRegistryOp(
    node,
    chain,
    registry,
    target,
    guardianSigner,
    idp.approvalBody(idp.OP_WITHDRAW, proposal, guardian.id),
    (auth) => idp.withdrawInput(target, proposal, guardian.id, auth),
    payer,
  );
}

export async function recover(
  node: ChainApi,
  chain: Uint8Array,
  registry: Uint8Array,
  target: Uint8Array,
  keyList: Uint8Array,
  payer: FeePayer | null = null,
): Promise<string> {
  return submitWithFee(node, chain, canonical(chain), { target: registry, input: idp.recoverInput(target, keyList) }, payer);
}

/** Where `guardian` stands on a proposal: approving, withdrawn (it cannot approve again), or neither. */
export type Standing = 'approved' | 'withdrawn' | null;

export interface ProposalStatus {
  proposal: Uint8Array;
  approvals: bigint;
  required: bigint;
  reachedAt: bigint;
  executableAt: bigint | null;
  /** The asking guardian's standing, when one was given. */
  standing: Standing;
  /**
   * Another proposal of this recovery epoch that the asking guardian approves.
   * A guardian approves one proposal at a time: it withdraws that approval
   * before it can approve this one.
   */
  approvesOther: Uint8Array | null;
}

/**
 * The recovery of `target` to `keyList` in its current recovery epoch. Any
 * guardian, threshold or delay change that applies starts a new epoch and
 * voids the proposal; reaching the threshold voids every pending change.
 */
export async function proposalStatus(
  node: ChainApi,
  registry: Uint8Array,
  target: Uint8Array,
  core: idp.Core,
  keyList: Uint8Array,
  guardian?: Uint8Array,
): Promise<ProposalStatus> {
  const proposal = idp.proposalHash(target, core.recoveryEpoch, keyList);
  const [record, approval = null, current = null] = await node.storageMany([
    { contract: registry, key: idp.proposalKey(target, proposal) },
    ...(guardian
      ? [
          { contract: registry, key: idp.approvalKey(target, proposal, guardian) },
          { contract: registry, key: idp.currentApprovalKey(target, core.recoveryEpoch, guardian) },
        ]
      : []),
  ]);
  const p = idp.decodeProposal(record);
  const req = idp.required(core);
  const standing: Standing = approval?.length === 1 ? (approval[0] === idp.APPROVED ? 'approved' : approval[0] === idp.WITHDRAWN ? 'withdrawn' : null) : null;
  return {
    proposal,
    approvals: p.approvals,
    required: req,
    reachedAt: p.reachedAt,
    executableAt: p.reachedAt > 0n && p.approvals >= req ? p.reachedAt + core.recoveryDelay : null,
    standing,
    approvesOther: current?.length === 32 && !equal(current, proposal) ? current : null,
  };
}

/** Which of the known identities guard `id` right now. */
/** The guardians of `me` among `candidates`, from the cache, read again with each block. */
export function useLinkedGuardians(me: { id: Uint8Array; registry: Uint8Array } | null, candidates: Uint8Array[]): Uint8Array[] {
  const { node, cache, version } = useChainData();
  const key = me ? `guardians:${hex(me.id)}` : null;
  const names = candidates.map(hex).sort();
  // The candidates are part of the version: other candidates are read again.
  const { values } = useCached<Uint8Array[]>(cache, key ? [key] : [], version && `${version}|${names.join(',')}`, async () => [
    await linkedGuardians(node, me!.registry, me!.id, candidates),
  ]);
  return (key ? (values.get(key) ?? []) : []).filter((g) => names.includes(hex(g)));
}

export async function linkedGuardians(node: ChainApi, registry: Uint8Array, id: Uint8Array, candidates: Uint8Array[]): Promise<Uint8Array[]> {
  if (candidates.length === 0) return [];
  const records = await node.storageMany(candidates.map((g) => ({ contract: registry, key: idp.guardianKey(id, g) })));
  return candidates.filter((_, i) => records[i] !== null);
}

export function userIdentity(user: User): { id: Uint8Array; registry: Uint8Array } | null {
  if (user.kind !== 'identity' || !user.identityId || !user.registry) return null;
  return { id: unhex(user.identityId), registry: unhex(user.registry) };
}

/** Which key of the identity a device key is, if any. */
export function linkedKeyId(c: Canonical, identity: IdentityInfo, key: KeyMeta): bigint | null {
  const policy = policyFor(c, key.scheme);
  const pub = unhex(key.pub);
  const hit = identity.keys.find((k) => equal(k.key.policy, policy) && equal(k.key.params, pub));
  return hit ? hit.keyId : null;
}

/**
 * Who a device key serves: the users it was made for, and identities that list
 * it on chain or, before they are on chain, in their creation. `unknown` names
 * identities whose keys are not read (not yet, or of another network): they
 * may list it too.
 */
export function keyUsers(key: KeyMeta, users: User[], identities: Map<string, IdentityInfo | null>, c: Canonical | null): { used: string[]; unknown: string[] } {
  const used: string[] = [];
  const unknown: string[] = [];
  for (const u of users) {
    if (u.keyId === key.id) {
      used.push(u.name);
      continue;
    }
    if (u.kind !== 'identity') continue;
    const identity = identities.get(u.id);
    if (!c || identity === undefined) unknown.push(u.name);
    else if (identity ? linkedKeyId(c, identity, key) !== null : creationLists(u, key)) used.push(u.name);
  }
  return { used, unknown };
}

function creationLists(user: User, key: KeyMeta): boolean {
  const creation = user.creation ? idp.decodeCreation(unhex(user.creation)) : null;
  return !!creation?.keys.some((k) => equal(k.params, unhex(key.pub)));
}

/** The device keys that can sign for the identity right now. */
export function linkedDeviceKeys(c: Canonical, identity: IdentityInfo, keys: KeyMeta[]): { key: KeyMeta; keyId: bigint }[] {
  const out: { key: KeyMeta; keyId: bigint }[] = [];
  for (const key of keys) {
    const keyId = linkedKeyId(c, identity, key);
    if (keyId !== null) out.push({ key, keyId });
  }
  return out;
}
