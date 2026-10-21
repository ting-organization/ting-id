// Everything the app learns from the node and sends to it. The app is a thin
// client: it reads records through the REST API, signs on the device and
// submits canonical transactions. It never interprets blocks itself.
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { equal, hex, unhex } from '@/chain/bytes';
import { policyAccount, signMessage } from '@/chain/accounts';
import { BatchCall, batchPrefix } from '@/chain/batch';
import { Canonical, canonical, contractCode, policyFor } from '@/chain/contracts';
import * as coin from '@/chain/coin';
import { nowSeconds } from '@/chain/format';
import { FeePayer, accountWitness, signerAccount, sponsoredCall } from '@/chain/fees';
import * as idp from '@/chain/identity';
import { Scheme } from '@/chain/keys';
import { ChainApi, MaybeSent, NodeStatus, TransactionLookup } from '@/chain/node';
import { NodePool } from '@/chain/pool';
import { CALL_BUDGET, CLAIM_BUDGET, EXECUTE_BUDGET, Resources, coinDeployBudget, contractId, encodeCall, encodeDeploy, transactionId } from '@/chain/tx';
import { ChainCache, invalidateCurrent, selectChain, useCached, useChainCache } from './cache';
import { loadSecret } from './keystore';
import { useStore } from './store';
import type { KeyMeta, User } from './types';
import { slotLabel } from './slots';

// ---- Nodes and chain --------------------------------------------------------

const pools = new Map<string, NodePool>();

/** The user's nodes used as one (chain/pool): one object for every screen, so what it learns of them is shared. */
export function poolFor(urls: string[]): NodePool {
  const key = urls.join(' ');
  let pool = pools.get(key);
  if (!pool) {
    pool = new NodePool(urls);
    // What came from a node found to give wrong answers is read again from the others.
    pool.onDistrust = invalidateCurrent;
    pools.set(key, pool);
  }
  return pool;
}

export function useNodePool(): NodePool {
  return poolFor(useStore((s) => s.settings.nodeUrls));
}

/** What the app asks of the chain: the user's nodes, as one. */
export function useNodeClient(): ChainApi {
  return useNodePool();
}

/** Every node's health and the pool's notice, for Settings; the screen renders again when they change. */
export function useNodes() {
  const pool = useNodePool();
  useSyncExternalStore(pool.subscribe, pool.getRevision);
  return { pool, nodes: pool.nodeHealth(), notice: pool.notice };
}

export interface ChainInfo {
  status: NodeStatus;
  canonical: Canonical;
}

/** How often the node's status is asked. */
const STATUS_MS = 8000;
/** The cache key of the last status, so the app starts with it. */
const STATUS_KEY = 'status';

/**
 * The status of the user's nodes, asked every 8 seconds while any screen
 * shows it, once for the whole app. The best node's goes into the cache of
 * the chain; `error` says no node can be used now.
 */
class StatusWatch {
  error: string | null = null;
  private users = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private busy = false;
  private listeners = new Set<() => void>();

  constructor(readonly pool: NodePool) {}

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getError = (): string | null => this.error;

  /** Asks while anyone watches: the returned function ends one watcher. */
  start = (): (() => void) => {
    if (this.users++ === 0) {
      this.poll();
      this.timer = setInterval(this.poll, STATUS_MS);
    }
    return () => {
      if (--this.users === 0 && this.timer) {
        clearInterval(this.timer);
        this.timer = null;
      }
    };
  };

  poll = async () => {
    if (this.busy) return;
    this.busy = true;
    try {
      const status = await this.pool.status();
      const cache = selectChain(hex(status.chainId));
      cache.put(STATUS_KEY, status, '');
      // Back after a pause: what failed meanwhile is read again.
      if (this.error !== null) {
        this.setError(null);
        cache.retry();
      }
    } catch (e) {
      this.setError((e as Error).message);
    } finally {
      this.busy = false;
    }
  };

  private setError(error: string | null) {
    if (this.error === error) return;
    this.error = error;
    for (const l of this.listeners) l();
  }
}

const watches = new Map<string, StatusWatch>();

function watchFor(pool: NodePool): StatusWatch {
  let watch = watches.get(pool.url);
  if (!watch) {
    watch = new StatusWatch(pool);
    watches.set(pool.url, watch);
  }
  return watch;
}

/**
 * The chain as the nodes last reported it, from the cache at first; `error`
 * while no node can be used, and `notice` when the nodes' answers did not
 * check out. `refresh` forgets what was found and reads everything again.
 */
export function useChainInfo() {
  const pool = useNodePool();
  const node: ChainApi = pool;
  const watch = watchFor(pool);
  const error = useSyncExternalStore(watch.subscribe, watch.getError);
  const notice = useSyncExternalStore(pool.subscribe, pool.getNotice);
  useEffect(() => watch.start(), [watch]);
  const cache = useChainCache();
  const status = cache?.get(STATUS_KEY)?.value as NodeStatus | undefined;
  const info: ChainInfo | null = useMemo(() => (status ? { status, canonical: canonical(status.chainId) } : null), [status]);
  const refresh = useCallback(() => {
    pool.reset();
    cache?.invalidate();
    watch.poll();
  }, [pool, cache, watch]);
  return { info, error, notice, refresh, node };
}

/** What the hooks reading the chain need: the node, the chain's cache, and the tip its values are read at. */
export function useChainData(): { node: ChainApi; cache: ChainCache | null; info: ChainInfo | null; version: string | null } {
  const { info, node } = useChainInfo();
  const cache = useChainCache();
  return { node, cache, info, version: info?.status.tip ?? null };
}

// ---- Coin accounts -----------------------------------------------------------

export async function fetchAccount(node: ChainApi, coinId: Uint8Array, account: Uint8Array): Promise<coin.AccountRecord> {
  return coin.decodeAccount(await node.storage(coinId, account));
}

/** Several coin accounts in one read: `reads` pairs a coin with an account. */
export async function fetchAccounts(node: ChainApi, reads: { coinId: Uint8Array; account: Uint8Array }[]): Promise<coin.AccountRecord[]> {
  if (reads.length === 0) return [];
  const values = await node.storageMany(reads.map((r) => ({ contract: r.coinId, key: r.account })));
  return values.map((v) => coin.decodeAccount(v));
}

export interface ScheduledItem {
  sequence: bigint;
  record: coin.ScheduledRecord;
}

/** Sequences or changes read in the first look back; later looks read a full batch. */
const FIRST_LOOK = 40n;
const LOOK = 1024n;

export interface ScheduledQuery {
  coinId: Uint8Array;
  account: Uint8Array;
  /** The account's nonce: its scheduled actions have sequences below it. */
  nonce: bigint;
  /** What its pending actions hold: the sum of their `reserved`. */
  locked: bigint;
  /** Read back to the first sequence: for a coin's minter, whose mints and minter changes may reserve nothing. */
  everything?: boolean;
}

/**
 * Scheduled actions still pending of several coin accounts, soonest first.
 * Each account's are read back from its nonce until the amounts they reserve
 * add up to its locked amount, which is exactly what its pending actions hold
 * (wiki: programs/coin), so none hides behind later ones; an account with
 * nothing locked has none to read.
 */
export async function fetchScheduledMany(node: ChainApi, of: ScheduledQuery[]): Promise<ScheduledItem[][]> {
  const found = of.map(() => [] as ScheduledItem[]);
  const held = of.map(() => 0n);
  const below = of.map((o) => o.nonce);
  const open = (i: number) => below[i] > 0n && (of[i].everything === true || held[i] < of[i].locked);
  for (let span = FIRST_LOOK; ; span = LOOK) {
    const reads: { i: number; sequence: bigint }[] = [];
    of.forEach((_, i) => {
      if (!open(i)) return;
      const from = below[i] > span ? below[i] - span : 0n;
      for (let sequence = below[i] - 1n; sequence >= from; sequence--) reads.push({ i, sequence });
      below[i] = from;
    });
    if (reads.length === 0) break;
    const values = await node.storageMany(reads.map((r) => ({ contract: of[r.i].coinId, key: coin.scheduledKey(of[r.i].account, r.sequence) })));
    reads.forEach((r, k) => {
      const bytes = values[k];
      const record = bytes && coin.decodeScheduled(bytes);
      if (!record) return;
      found[r.i].push({ sequence: r.sequence, record });
      held[r.i] += record.reserved;
    });
  }
  return found.map((list) => list.sort((a, b) => (a.record.executeAt < b.record.executeAt ? -1 : a.record.executeAt > b.record.executeAt ? 1 : 0)));
}

// ---- Identities ----------------------------------------------------------------

export interface IdentityInfo {
  id: Uint8Array;
  registry: Uint8Array;
  core: idp.Core;
  keys: { keyId: bigint; key: idp.IdKey }[];
  slots: { slot: number; delay: number }[];
  pending: { changeNo: bigint; record: idp.ChangeRecord }[];
}

/** Reads of an identity that may find its core changed before it gives up. */
const IDENTITY_TRIES = 5;

/**
 * An identity as the registry has it: the core record, then the core again
 * with its keys, slots and newest changes in one read, so they all come from
 * the same block. A core that changed in between is read from again. Changes
 * further back, when there are many, take more reads.
 */
export async function fetchIdentity(node: ChainApi, registry: Uint8Array, id: Uint8Array): Promise<IdentityInfo | null> {
  const coreRead = { contract: registry, key: idp.coreKey(id) };
  let coreBytes = await node.storage(registry, idp.coreKey(id));
  for (let tries = 0; ; tries++) {
    // A core that changes on every read is not one to wait for: a block is a minute.
    if (tries === IDENTITY_TRIES) throw new Error('the identity kept changing while it was read: try again');
    const core = coreBytes && idp.decodeCore(coreBytes);
    if (!core) return null;
    const keyIds: bigint[] = [];
    for (let k = 0n; k < core.nextKeyId; k++) keyIds.push(k);
    const slotNos: number[] = [];
    for (let slot = 0; slot < Number(core.slotCount); slot++) slotNos.push(slot);
    const firstChanges = changeNos(core.nextChange, FIRST_LOOK);
    const values = await node.storageMany([
      coreRead,
      ...keyIds.map((k) => ({ contract: registry, key: idp.keyKey(id, core.keyEpoch, k) })),
      ...slotNos.map((slot) => ({ contract: registry, key: idp.slotKey(id, slot) })),
      ...firstChanges.map((c) => ({ contract: registry, key: idp.changeKey(id, c) })),
    ]);
    if (!values[0] || !equal(values[0], coreBytes!)) {
      coreBytes = values[0];
      continue;
    }
    let next = 1;
    const keys: IdentityInfo['keys'] = [];
    for (const keyId of keyIds) {
      const b = values[next++];
      const key = b && idp.decodeKeyRecord(b);
      if (key) keys.push({ keyId, key });
    }
    const slots: IdentityInfo['slots'] = slotNos.map((slot) => {
      const b = values[next++];
      return { slot, delay: b && b.length === 8 ? Number(new DataView(b.buffer, b.byteOffset).getBigUint64(0, true)) : 0 };
    });
    // Changes are read back from the newest until one of an earlier change
    // epoch: every change before it is older still, and none of those is pending.
    const changes: IdentityInfo['pending'] = [];
    let older = false;
    const take = (nos: bigint[], records: (Uint8Array | null)[]) =>
      nos.forEach((changeNo, k) => {
        const bytes = records[k];
        const record = bytes && idp.decodeChangeRecord(bytes);
        if (!record) return;
        if (record.changeEpoch < core.changeEpoch) older = true;
        else changes.push({ changeNo, record });
      });
    take(firstChanges, values.slice(next));
    for (let below = core.nextChange - BigInt(firstChanges.length); below > 0n && !older; below -= LOOK > below ? below : LOOK) {
      const nos = changeNos(below, LOOK);
      take(nos, await node.storageMany(nos.map((c) => ({ contract: registry, key: idp.changeKey(id, c) }))));
    }
    changes.sort((a, b) => (a.changeNo < b.changeNo ? -1 : 1));
    // A change stays pending only while the key that proposed it does.
    const current = keys.map((k) => k.keyId);
    const pending = changes.filter((c) => idp.isPending(c.record, core, current));
    return { id, registry, core, keys, slots, pending };
  }
}

/** The `span` change numbers below `below`, newest first. */
function changeNos(below: bigint, span: bigint): bigint[] {
  const from = below > span ? below - span : 0n;
  const nos: bigint[] = [];
  for (let c = below - 1n; c >= from; c--) nos.push(c);
  return nos;
}

/** The identity of `user` from the cache, read again with each block; null when not on chain (yet). */
export function useIdentity(user: User | null) {
  const { node, cache, version } = useChainData();
  const registryHex = user?.kind === 'identity' ? user.registry : undefined;
  const idHex = user?.kind === 'identity' ? user.identityId : undefined;
  const key = registryHex && idHex ? `identity:${registryHex}:${idHex}` : null;
  const { values } = useCached<IdentityInfo | null>(cache, key ? [key] : [], version, async () => [await fetchIdentity(node, unhex(registryHex!), unhex(idHex!))]);
  return { identity: key ? (values.get(key) ?? null) : null, loading: key !== null && !values.has(key) };
}

/**
 * The identities of `users` on the chain the app is on, by user id (null: not
 * on chain). A user whose identity is not read yet, or is of another network,
 * is not in the map.
 */
export function useIdentities(users: User[]): Map<string, IdentityInfo | null> {
  const { node, cache, version } = useChainData();
  const chain = cache?.chain;
  const wanted = useMemo(
    () =>
      users.flatMap((u) =>
        u.kind === 'identity' && u.registry && u.identityId && (!u.chainId || u.chainId === chain) ? [{ user: u.id, key: `identity:${u.registry}:${u.identityId}` }] : [],
      ),
    [users, chain],
  );
  const keys = useMemo(() => [...new Set(wanted.map((w) => w.key))], [wanted]);
  const { values } = useCached<IdentityInfo | null>(cache, keys, version, (stale) =>
    Promise.all(
      stale.map((k) => {
        const [, registry, id] = k.split(':');
        return fetchIdentity(node, unhex(registry), unhex(id));
      }),
    ),
  );
  return useMemo(() => new Map(wanted.filter((w) => values.has(w.key)).map((w) => [w.user, values.get(w.key) ?? null])), [wanted, values]);
}

// ---- Accounts of a user ------------------------------------------------------

export interface UserAccount {
  slot: number;
  label: string;
  account: Uint8Array;
  delay: number;
}

export { SLOT_LABELS, slotLabel } from './slots';

/** The accounts a user can act from: one per identity slot, or the key's own account. */
export function userAccounts(user: User, c: Canonical, keys: KeyMeta[], identity: IdentityInfo | null): UserAccount[] {
  if (user.kind === 'key') {
    const key = keys.find((k) => k.id === user.keyId);
    if (!key) return [];
    return [{ slot: 0, label: 'Account', account: policyAccount(policyFor(c, key.scheme), unhex(key.pub)), delay: 0 }];
  }
  const registry = unhex(user.registry!);
  const id = unhex(user.identityId!);
  const slots = identity?.slots ?? settingsSlots(user);
  return slots.map(({ slot, delay }) => ({ slot, label: slotLabel(slot), account: idp.account(registry, id, slot), delay }));
}

function settingsSlots(user: User): { slot: number; delay: number }[] {
  const s = user.creation ? idp.decodeCreation(unhex(user.creation)) : null;
  return (s?.slots ?? idp.DEFAULT_SLOTS).map((delay, slot) => ({ slot, delay }));
}

// ---- Signing and submitting --------------------------------------------------

export interface Signer {
  key: KeyMeta;
  secret: Uint8Array;
  /** For identity accounts: which key of the identity this device key is. */
  keyId?: bigint;
  policy: Uint8Array;
  params: Uint8Array;
}

/** Finds a device key that may sign for `user` (as `keyId` of its identity). */
export async function resolveSigner(
  user: User,
  slot: number,
  c: Canonical,
  keys: KeyMeta[],
  identity: IdentityInfo | null,
  preferredKeyId?: string,
): Promise<Signer> {
  const withSecret = async (key: KeyMeta) => {
    const secretHex = await loadSecret(key.id);
    if (!secretHex) throw new Error(`the secret of key "${key.name}" is missing from this device`);
    return unhex(secretHex);
  };
  if (user.kind === 'key') {
    const key = keys.find((k) => k.id === user.keyId);
    if (!key) throw new Error('this user’s key is no longer on the device');
    return { key, secret: await withSecret(key), policy: policyFor(c, key.scheme), params: unhex(key.pub) };
  }
  if (!identity) throw new Error('the identity is not on chain yet');
  // The key this device made for the user comes first; any linked key will do.
  const candidates = preferredKeyId
    ? keys.filter((k) => k.id === preferredKeyId)
    : [...keys].sort((a, b) => Number(b.id === user.keyId) - Number(a.id === user.keyId));
  for (const { keyId, key } of identity.keys) {
    const match = candidates.find((k) => equal(policyFor(c, k.scheme), key.policy) && equal(unhex(k.pub), key.params));
    if (match) {
      return {
        key: match,
        secret: await withSecret(match),
        keyId,
        policy: unhex(user.registry!),
        params: idp.params(unhex(user.identityId!), slot),
      };
    }
  }
  throw new Error('no key on this device can sign for this identity');
}

export function witnessFor(signer: Signer, message: Uint8Array): Uint8Array {
  return accountWitness(signer, message);
}

// ---- Fees --------------------------------------------------------------------
//
// Every operation can pay a fee in the fee coin (Settings), from one of the
// user's instant accounts, never a delayed one. An action of that account in
// that coin carries the fee itself; anything else goes in a batch with, last, a
// payment bound to it, so the miner is paid only for including the operation.

/** Whether `payer` is the acting account itself in the same coin, which then pays its fee directly. */
function paysOwnFee(payer: FeePayer | null, coinId: Uint8Array, account: Uint8Array): payer is FeePayer {
  return !!payer && payer.fee > 0n && equal(payer.coinId, coinId) && equal(signerAccount(payer.signer), account);
}

const nonceKey = (coinId: Uint8Array, account: Uint8Array) => `${hex(coinId)}:${hex(account)}`;

/**
 * The nonce for the next action of a coin account. While this device's last
 * action of the account still waits in the node, the next one takes the nonce
 * after it and so queues behind it instead of competing for the same nonce.
 * `prefix`: a fee payment for the same calls as the last one reuses its nonce,
 * so the transaction is the same.
 */
export async function nextNonce(node: ChainApi, coinId: Uint8Array, account: Uint8Array, prefix?: string): Promise<bigint> {
  const note = useStore.getState().nonces[nonceKey(coinId, account)];
  // The earlier transaction is looked up before the nonce is read: if a block
  // takes it in between, the nonce read already counts it. One that cannot be
  // looked up counts as waiting, so no nonce is ever signed twice.
  const waiting = note ? await node.transaction(note.txId).then((l) => l?.pending === true, () => true) : false;
  const { nonce: current } = await fetchAccount(node, coinId, account);
  // Past its expiry the noted transaction can never go in, so its nonce is free again.
  if (!note || !waiting || BigInt(note.nonce) < current || nowSeconds() - note.at > SIGNED_LIFE) return current;
  return prefix !== undefined && note.prefix === prefix ? BigInt(note.nonce) : BigInt(note.nonce) + 1n;
}

function noteNonce(coinId: Uint8Array, account: Uint8Array, nonce: bigint, txId: string, prefix?: string) {
  useStore.getState().noteNonce(nonceKey(coinId, account), { nonce: nonce.toString(), txId, at: nowSeconds(), prefix });
}

/** The longest a signed action stays valid: `signedExpiry` is at most two days after signing. */
const SIGNED_LIFE = 2 * 86_400;

/**
 * When what this app signs expires unless included: the end of the next day
 * (UTC), so the same action signed again that day is the same transaction. A
 * signed action that never went in cannot be used later (wiki: programs/coin,
 * expiry); one already scheduled still runs when due.
 */
function signedExpiry(): bigint {
  return BigInt((Math.floor(nowSeconds() / 86_400) + 2) * 86_400);
}

/**
 * Submits `call` alone with its usual budget or, with a fee, as a batch that
 * ends with the payer's bound payment, and then the call alone as well,
 * unpaid, so it does not depend on the payment (wiki: programs/conventions):
 * someone else holding a key of the paying account cannot make it fail by
 * using that account's nonce first. The batch's id is returned; the call's
 * own id is kept with it, and whichever goes in settles the submission.
 */
export async function submitWithFee(
  node: ChainApi,
  chain: Uint8Array,
  c: Canonical,
  call: BatchCall,
  payer: FeePayer | null,
  budget: Resources = CALL_BUDGET,
): Promise<string> {
  const alone = encodeCall(call.target, call.input, budget);
  if (!payer || payer.fee === 0n) return node.submit(alone);
  const account = signerAccount(payer.signer);
  const prefix = hex(batchPrefix([call]));
  const nonce = await nextNonce(node, payer.coinId, account, prefix);
  // A transaction the node may have, its answer lost, counts as sent but not for sure.
  let paid: string | null = null;
  let sure = false;
  let refused: unknown = null;
  try {
    paid = await node.submit(sponsoredCall(chain, c, [call], payer, nonce, signedExpiry()));
    sure = true;
  } catch (e) {
    if (e instanceof MaybeSent) paid = e.txId;
    else refused = e;
  }
  if (paid) noteNonce(payer.coinId, account, nonce, paid, prefix);
  let unpaid: string | null = null;
  try {
    unpaid = await node.submit(alone);
    sure = true;
  } catch (e) {
    if (e instanceof MaybeSent) unpaid = e.txId;
  }
  if (paid && unpaid) useStore.getState().pairOutbox(paid, unpaid);
  const id = paid ?? unpaid;
  if (!id) throw refused;
  if (!sure) throw new MaybeSent(id);
  return id;
}

/**
 * Runs a submission. One whose answer was lost still gives its id: the node
 * may have it, and it is kept to be sent again with the same bytes.
 */
/** A submission's id, and whether its answer was lost (see `submission`). */
export async function sent(f: () => Promise<string | { txId: string }>): Promise<{ txId: string; unsure: boolean }> {
  const out = await submission(f);
  if (out.unsure) return { txId: out.txId, unsure: true };
  return { txId: typeof out.result === 'string' ? out.result : out.result.txId, unsure: false };
}

/** The params that open a submission's screen, with the warning when its answer was lost. */
export function txParams(s: { txId: string; unsure: boolean }, extra: Record<string, string> = {}): Record<string, string> {
  return { id: s.txId, ...extra, ...(s.unsure ? { unsure: '1' } : {}) };
}

export async function submission<T extends string | { txId: string }>(f: () => Promise<T>): Promise<{ result: T; unsure: false } | { txId: string; unsure: true; action?: MaybeSent['action'] }> {
  try {
    return { result: await f(), unsure: false };
  } catch (e) {
    if (e instanceof MaybeSent) return { txId: e.txId, unsure: true, action: e.action };
    throw e;
  }
}

export interface Submitted {
  txId: string;
  account: Uint8Array;
  nonce: bigint;
}

/**
 * Signs a coin action for the signer's account and submits it. A delayed
 * account must use fee 0; `payer` then pays for it from another account. A
 * payer that is the acting account itself adds its fee to the action. An
 * action without its own expiry gets the usual one.
 */
export async function submitCoinAction(
  node: ChainApi,
  chain: Uint8Array,
  coinId: Uint8Array,
  signer: Signer,
  action: coin.Action,
  nonceOverride?: bigint,
  payer: FeePayer | null = null,
): Promise<Submitted> {
  const account = policyAccount(signer.policy, signer.params);
  const nonce = nonceOverride ?? (await nextNonce(node, coinId, account));
  const own = paysOwnFee(payer, coinId, account);
  const timing = { ...action.timing, validUntil: action.timing.validUntil || signedExpiry() };
  const signed = { ...action, timing: own ? { ...timing, fee: timing.fee + payer.fee } : timing };
  const message = coin.coinMessage(chain, coinId, account, nonce, signed);
  const input = coin.executeInput({ policy: signer.policy, params: signer.params, nonce, witness: witnessFor(signer, message) }, signed);
  let txId: string;
  try {
    txId = await submitWithFee(node, chain, canonical(chain), { target: coinId, input }, own ? null : payer, EXECUTE_BUDGET);
  } catch (e) {
    // Its nonce is taken all the same, so nothing is ever signed again with it.
    if (e instanceof MaybeSent) {
      noteNonce(coinId, account, nonce, e.txId);
      throw new MaybeSent(e.txId, { account, nonce });
    }
    throw e;
  }
  noteNonce(coinId, account, nonce, txId);
  return { txId, account, nonce };
}

/**
 * Cancels scheduled actions of the signer's account with one signature (op 11).
 * The cancel carries no nonce. Its fee is 0 unless `payer` is the account
 * itself (an instant one), which then pays it directly; any other payer pays
 * through a batch, as it must for a delayed account.
 */
export async function submitCoinCancel(
  node: ChainApi,
  chain: Uint8Array,
  coinId: Uint8Array,
  signer: Signer,
  sequences: bigint[],
  payer: FeePayer | null = null,
): Promise<{ txId: string; account: Uint8Array }> {
  const account = policyAccount(signer.policy, signer.params);
  const own = paysOwnFee(payer, coinId, account);
  const sorted = [...sequences].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const cancel: coin.Cancel = { sequences: sorted, fee: own ? payer.fee : 0n, validUntil: signedExpiry() };
  const message = coin.cancelMessage(chain, coinId, account, cancel);
  const input = coin.cancelInput({ policy: signer.policy, params: signer.params, witness: witnessFor(signer, message) }, cancel);
  const txId = await submitWithFee(node, chain, canonical(chain), { target: coinId, input }, own ? null : payer, EXECUTE_BUDGET);
  return { txId, account };
}

/**
 * Deploys a new coin with `options`. A deployment names no sender and carries
 * no fee: it waits for room in a block. Its ContractId follows from the
 * transaction, so it is known before a block includes it.
 */
export async function deployCoin(node: ChainApi, chain: Uint8Array, options: coin.CoinOptions): Promise<{ txId: string; coinId: Uint8Array }> {
  const problem = coin.deployProblem(options);
  if (problem) throw new Error(problem);
  const tx = encodeDeploy(contractCode('coin'), coin.encodeDeployInput(options), coinDeployBudget(options.allocations.length));
  const txId = await node.submit(tx);
  return { txId, coinId: contractId(chain, tx) };
}

/** Submits an unsigned coin call (run, claim). */
export async function submitCoinCall(node: ChainApi, coinId: Uint8Array, input: Uint8Array): Promise<string> {
  return node.submit(encodeCall(coinId, input, CALL_BUDGET));
}

// ---- Running scheduled actions --------------------------------------------------
//
// The run of a scheduled action is an unsigned call that anyone may submit once
// the action is due. Wallets hand it to the node right away as a deferred
// transaction: the node holds it and includes it once it is due and succeeds.
// Every wallet builds the same bytes (the CLI too), so queues merge by id.

export function runTransaction(coinId: Uint8Array, account: Uint8Array, sequence: bigint): Uint8Array {
  return encodeCall(coinId, coin.targetInput(coin.OP_RUN, account, sequence), CLAIM_BUDGET);
}

/** Queues the run on the node for when the action is due (`notBefore`, seconds). */
export async function queueRun(node: ChainApi, coinId: Uint8Array, account: Uint8Array, sequence: bigint, notBefore: number): Promise<string> {
  return node.submit(runTransaction(coinId, account, sequence), notBefore);
}

/** Submits the run now (for an action that is due). */
export async function submitRun(node: ChainApi, coinId: Uint8Array, account: Uint8Array, sequence: bigint): Promise<string> {
  return node.submit(runTransaction(coinId, account, sequence));
}

/** queued: held by the node until due; pending: in the pool; ran: in a block. */
export type RunStatus = 'queued' | 'pending' | 'ran' | null;

export async function fetchRunStatus(node: ChainApi, chain: Uint8Array, coinId: Uint8Array, account: Uint8Array, sequence: bigint): Promise<RunStatus> {
  const lookup = await node.transaction(hex(transactionId(chain, runTransaction(coinId, account, sequence))));
  if (!lookup) return null;
  if (lookup.occurrences.some((o) => o.receipt.status === 0)) return 'ran';
  if (lookup.pending) return 'pending';
  if (lookup.deferred) return 'queued';
  return null;
}

export interface RunRef {
  coinId: Uint8Array;
  account: Uint8Array;
  sequence: bigint;
}

export function runKey(r: RunRef): string {
  return `${hex(r.coinId)}:${hex(r.account)}:${r.sequence}`;
}

/** The run status of each scheduled action that has one known, by `runKey`, from the cache; read again with each block. */
export function useRunStatuses(refs: RunRef[]): Map<string, RunStatus> {
  const { node, cache, version, info } = useChainData();
  const keys = refs.map((r) => `run:${runKey(r)}`);
  const { values } = useCached<RunStatus>(cache, keys, version, (stale) =>
    Promise.all(
      stale.map((k) => {
        const r = refs[keys.indexOf(k)];
        return fetchRunStatus(node, info!.status.chainId, r.coinId, r.account, r.sequence);
      }),
    ),
  );
  return useMemo(() => new Map([...values].map(([k, v]) => [k.slice('run:'.length), v])), [values]);
}

/**
 * A registry operation on `id` signed by key `signer.keyId` (of `id` itself, or
 * of the guardian for approvals and withdrawals); the message names that key.
 */
export async function submitRegistryOp(
  node: ChainApi,
  chain: Uint8Array,
  registry: Uint8Array,
  id: Uint8Array,
  signer: { scheme: Scheme; secret: Uint8Array; keyId: bigint },
  body: Uint8Array,
  build: (auth: Uint8Array) => Uint8Array,
  payer: FeePayer | null = null,
): Promise<string> {
  const message = idp.registryMessage(chain, registry, id, signer.keyId, body);
  const auth = idp.keyAuth(signer.keyId, signMessage(signer.scheme, signer.secret, message));
  return submitWithFee(node, chain, canonical(chain), { target: registry, input: build(auth) }, payer);
}

export async function submitRegistryCall(node: ChainApi, registry: Uint8Array, input: Uint8Array): Promise<string> {
  return node.submit(encodeCall(registry, input, CALL_BUDGET));
}

// ---- Transaction status ---------------------------------------------------

export function useTransaction(txId: string | null, pollMs = 2500) {
  const node = useNodeClient();
  const [lookup, setLookup] = useState<TransactionLookup | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The node answered that it does not know the transaction.
  const [missing, setMissing] = useState(false);
  useEffect(() => {
    let alive = true;
    if (!txId) return;
    const load = async () => {
      try {
        const l = await node.transaction(txId);
        if (!alive) return;
        setLookup(l);
        setMissing(l === null);
        setError(null);
        if (l && l.occurrences.length > 0 && !l.pending) clearInterval(timer);
      } catch (e) {
        if (alive) setError((e as Error).message);
      }
    };
    const timer = setInterval(load, pollMs);
    load();
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [node, txId, pollMs]);
  return { lookup, error, missing };
}

// ---- Activity ---------------------------------------------------------------------

export interface ActivityItem {
  height: number;
  /** The block's timestamp, in seconds. */
  time: number;
  transaction: string;
  contract: Uint8Array;
  coin?: coin.CoinEvent;
  identity?: idp.IdEvent;
  /** For a scheduled action: whether a later block ran it or cancelled it. */
  settled?: 'executed' | 'cancelled';
  /** For a transfer: it paid a block producer, a fee or a tip, and no other event of its transaction is shown to carry it. */
  fee?: boolean;
  /** The fees its transaction paid from the user's accounts, in their coins. */
  fees?: { contract: Uint8Array; amount: bigint }[];
  /** For a scheduled action that ran or was cancelled: what it was to do, when its scheduling is among the items. */
  plan?: coin.Effect;
  /** For payments to a miner grouped by day: how many the row stands for, its amount their sum. */
  count?: number;
}

