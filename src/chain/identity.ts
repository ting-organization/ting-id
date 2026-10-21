// The identity registry reference program (wiki: programs/identity):
// identifiers, records, operations, messages and events.
import { Reader, Writer, compare, concat, equal, strictlyIncreasing, tryDecode, u64le, utf8 } from './bytes';
import { taggedHash } from './hash';
import { policyAccount } from './accounts';

export const APP_TAG = 'app/id-v1';
export const VERSION = 1;
export const PARAMS_LEN = 40;

export const OP_CREATE = 1;
export const OP_PROPOSE = 2;
export const OP_CANCEL = 3;
export const OP_APPLY = 4;
export const OP_APPROVE = 5;
export const OP_WITHDRAW = 6;
export const OP_RECOVER = 7;
export const OP_INFO = 8;

const KIND_CORE = 0;
const KIND_KEY = 1;
const KIND_GUARDIAN = 2;
const KIND_SLOT = 3;
const KIND_CHANGE = 4;
const KIND_PROPOSAL = 5;
const KIND_APPROVAL = 6;
const KIND_CURRENT_APPROVAL = 7;

export const APPROVED = 1;
export const WITHDRAWN = 2;

export const DEFAULT_CHANGE_DELAY = 604_800;
export const DEFAULT_RECOVERY_DELAY = 172_800;
export const DEFAULT_SLOTS = [0, 604_800];

export const CREATED_TOPIC = taggedHash('app/id-v1/event/created');
export const CHANGE_PROPOSED_TOPIC = taggedHash('app/id-v1/event/change-proposed');
export const CHANGE_CANCELLED_TOPIC = taggedHash('app/id-v1/event/change-cancelled');
export const CHANGE_APPLIED_TOPIC = taggedHash('app/id-v1/event/change-applied');
export const RECOVERY_APPROVED_TOPIC = taggedHash('app/id-v1/event/recovery-approved');
export const RECOVERY_WITHDRAWN_TOPIC = taggedHash('app/id-v1/event/recovery-withdrawn');
export const RECOVERED_TOPIC = taggedHash('app/id-v1/event/recovered');

// ---- Identifiers and record keys ---------------------------------------------

/** The ID of a creation input (everything after the op byte). */
export function idOf(creation: Uint8Array): Uint8Array {
  return taggedHash('app/id-v1/id', creation);
}

/** Policy parameters of an ID account: `id ‖ u64 slot`. */
export function params(id: Uint8Array, slot: number): Uint8Array {
  return concat(id, u64le(slot));
}

export function account(registry: Uint8Array, id: Uint8Array, slot: number): Uint8Array {
  return policyAccount(registry, params(id, slot));
}

function record(kind: number, id: Uint8Array, index: Uint8Array): Uint8Array {
  return taggedHash('app/id-v1/record', Uint8Array.of(kind), id, index);
}
export const coreKey = (id: Uint8Array) => record(KIND_CORE, id, new Uint8Array());
export const keyKey = (id: Uint8Array, epoch: bigint | number, keyId: bigint | number) =>
  record(KIND_KEY, id, concat(u64le(epoch), u64le(keyId)));
export const guardianKey = (id: Uint8Array, guardian: Uint8Array) => record(KIND_GUARDIAN, id, guardian);
export const slotKey = (id: Uint8Array, slot: number) => record(KIND_SLOT, id, u64le(slot));
export const changeKey = (id: Uint8Array, changeNo: bigint | number) => record(KIND_CHANGE, id, u64le(changeNo));
export const proposalKey = (id: Uint8Array, proposal: Uint8Array) => record(KIND_PROPOSAL, id, proposal);
export const approvalKey = (id: Uint8Array, proposal: Uint8Array, guardian: Uint8Array) => record(KIND_APPROVAL, id, concat(proposal, guardian));
/** The proposal `guardian` approves in a recovery epoch; a guardian approves one at a time. */
export const currentApprovalKey = (id: Uint8Array, recoveryEpoch: bigint | number, guardian: Uint8Array) =>
  record(KIND_CURRENT_APPROVAL, id, concat(u64le(recoveryEpoch), guardian));

/** A recovery proposal: the new key set, bound to the ID's recovery epoch. */
export function proposalHash(id: Uint8Array, recoveryEpoch: bigint | number, keyList: Uint8Array): Uint8Array {
  return taggedHash('app/id-v1/recovery', id, u64le(recoveryEpoch), keyList);
}

// ---- Records -----------------------------------------------------------------

export interface Core {
  opNonce: bigint;
  changeEpoch: bigint;
  recoveryEpoch: bigint;
  keyEpoch: bigint;
  nextKeyId: bigint;
  keyCount: bigint;
  guardianCount: bigint;
  slotCount: bigint;
  threshold: bigint;
  changeDelay: bigint;
  recoveryDelay: bigint;
  nextChange: bigint;
}

export function decodeCore(bytes: Uint8Array): Core | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    if (r.u8() !== VERSION) throw new RangeError('version');
    const c: Core = {
      opNonce: r.u64(),
      changeEpoch: r.u64(),
      recoveryEpoch: r.u64(),
      keyEpoch: r.u64(),
      nextKeyId: r.u64(),
      keyCount: r.u64(),
      guardianCount: r.u64(),
      slotCount: r.u64(),
      threshold: r.u64(),
      changeDelay: r.u64(),
      recoveryDelay: r.u64(),
      nextChange: r.u64(),
    };
    r.end();
    return c;
  });
}

export function encodeCore(c: Core): Uint8Array {
  return new Writer()
    .u8(VERSION)
    .u64(c.opNonce)
    .u64(c.changeEpoch)
    .u64(c.recoveryEpoch)
    .u64(c.keyEpoch)
    .u64(c.nextKeyId)
    .u64(c.keyCount)
    .u64(c.guardianCount)
    .u64(c.slotCount)
    .u64(c.threshold)
    .u64(c.changeDelay)
    .u64(c.recoveryDelay)
    .u64(c.nextChange)
    .finish();
}

/** Approvals a recovery needs. */
export function required(c: Core): bigint {
  return c.threshold > 0n ? c.threshold : c.guardianCount / 2n + 1n;
}

export interface IdKey {
  policy: Uint8Array;
  params: Uint8Array;
}

export function decodeKeyRecord(bytes: Uint8Array): IdKey | null {
  if (bytes.length < 32) return null;
  return { policy: bytes.slice(0, 32), params: bytes.slice(32) };
}

export function encodeKeyRecord(k: IdKey): Uint8Array {
  return concat(k.policy, k.params);
}

/** `u64 count ‖ count × (policy ‖ Bytes(params))`. */
export function encodeKeyList(keys: IdKey[]): Uint8Array {
  const w = new Writer().u64(keys.length);
  for (const k of keys) w.put(k.policy).bytes(k.params);
  return w.finish();
}

export function decodeKeyList(bytes: Uint8Array): IdKey[] | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    const keys = readKeyList(r);
    r.end();
    return keys;
  });
}

function readKeyList(r: Reader): IdKey[] {
  const count = r.u64n();
  const keys: IdKey[] = [];
  for (let i = 0; i < count; i++) keys.push({ policy: r.array32(), params: new Uint8Array(r.bytes()) });
  return keys;
}

export interface Settings {
  keys: IdKey[];
  guardians: Uint8Array[];
  threshold: number;
  changeDelay: number;
  recoveryDelay: number;
  slots: number[];
  salt: Uint8Array;
}

/** Guardians in the order a creation input lists them: strictly increasing bytes. */
export function sortGuardians(guardians: Uint8Array[]): Uint8Array[] {
  return [...guardians].sort(compare);
}

/** Creation input, after the op byte. Guardians must already be sorted ({@link sortGuardians}). */
export function encodeCreation(s: Settings): Uint8Array {
  const w = new Writer().u8(VERSION).put(encodeKeyList(s.keys)).u64(s.guardians.length);
  for (const g of s.guardians) w.put(g);
  w.u64(s.threshold).u64(s.changeDelay).u64(s.recoveryDelay).u64(s.slots.length);
  for (const d of s.slots) w.u64(d);
  return w.put(s.salt).finish();
}

export function decodeCreation(bytes: Uint8Array): Settings | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    if (r.u8() !== VERSION) throw new RangeError('version');
    const keys = readKeyList(r);
    const g = r.items(32);
    const guardians: Uint8Array[] = [];
    for (let i = 0; i < g.length; i += 32) guardians.push(new Uint8Array(g.subarray(i, i + 32)));
    const threshold = r.u64n();
    const changeDelay = r.u64n();
    const recoveryDelay = r.u64n();
    const sl = r.items(8);
    const slots: number[] = [];
    for (let i = 0; i < sl.length; i += 8) slots.push(new Reader(sl.subarray(i, i + 8)).u64n());
    const salt = r.array32();
    r.end();
    return { keys, guardians, threshold, changeDelay, recoveryDelay, slots, salt };
  });
}

/** The registry's configuration rules: guardians in strictly increasing byte order (so none twice), never the id itself. */
export function validSettings(s: Settings, id: Uint8Array): string | null {
  if (s.keys.length === 0) return 'at least one key';
  if (s.slots.length === 0) return 'at least one slot';
  if (s.threshold !== 0 && s.threshold > s.guardians.length) return 'threshold above the guardian count';
  if (s.recoveryDelay >= s.changeDelay) return 'recovery delay must be below the change delay';
  for (let i = 0; i < s.guardians.length; i++) {
    if (equal(s.guardians[i], id)) return 'an identity cannot guard itself';
    if (i > 0) {
      const order = compare(s.guardians[i - 1], s.guardians[i]);
      if (order === 0) return 'guardians must be distinct';
      if (order > 0) return 'guardians must be sorted';
    }
  }
  return null;
}

// ---- Changes -------------------------------------------------------------------

export type Operation =
  | { kind: 'addKey'; key: IdKey }
  | { kind: 'removeKey'; keyId: bigint }
  | { kind: 'addGuardian'; id: Uint8Array }
  | { kind: 'removeGuardian'; id: Uint8Array }
  | { kind: 'setThreshold'; threshold: bigint }
  | { kind: 'setDelays'; changeDelay: bigint; recoveryDelay: bigint }
  | { kind: 'setSlot'; slot: bigint; delay: bigint };

function encodeOperation(w: Writer, op: Operation): void {
  switch (op.kind) {
    case 'addKey':
      w.u8(1).put(op.key.policy).bytes(op.key.params);
      break;
    case 'removeKey':
      w.u8(2).u64(op.keyId);
      break;
    case 'addGuardian':
      w.u8(3).put(op.id);
      break;
    case 'removeGuardian':
      w.u8(4).put(op.id);
      break;
    case 'setThreshold':
      w.u8(5).u64(op.threshold);
      break;
    case 'setDelays':
      w.u8(6).u64(op.changeDelay).u64(op.recoveryDelay);
      break;
    case 'setSlot':
      w.u8(7).u64(op.slot).u64(op.delay);
      break;
  }
}

function readOperation(r: Reader): Operation {
  switch (r.u8()) {
    case 1:
      return { kind: 'addKey', key: { policy: r.array32(), params: new Uint8Array(r.bytes()) } };
    case 2:
      return { kind: 'removeKey', keyId: r.u64() };
    case 3:
      return { kind: 'addGuardian', id: r.array32() };
    case 4:
      return { kind: 'removeGuardian', id: r.array32() };
    case 5:
      return { kind: 'setThreshold', threshold: r.u64() };
    case 6:
      return { kind: 'setDelays', changeDelay: r.u64(), recoveryDelay: r.u64() };
    case 7:
      return { kind: 'setSlot', slot: r.u64(), delay: r.u64() };
    default:
      throw new RangeError('unknown operation');
  }
}

/** `u64 count ‖ count × operation`. */
export function encodeChange(ops: Operation[]): Uint8Array {
  const w = new Writer().u64(ops.length);
  for (const op of ops) encodeOperation(w, op);
  return w.finish();
}

function readChange(r: Reader): Operation[] {
  const count = r.u64n();
  const ops: Operation[] = [];
  for (let i = 0; i < count; i++) ops.push(readOperation(r));
  return ops;
}

export function decodeChange(bytes: Uint8Array): Operation[] | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    const ops = readChange(r);
    r.end();
    return ops;
  });
}

/** Change record: `u64 change_epoch ‖ u64 proposer ‖ u64 proposed_at ‖ u64 effective_at ‖ change`. */
export interface ChangeRecord {
  changeEpoch: bigint;
  /** The key_id of the key that proposed it: the change dies with that key. */
  proposer: bigint;
  /** Block timestamp of the proposal. */
  proposedAt: bigint;
  effectiveAt: bigint;
  operations: Operation[];
}

export function decodeChangeRecord(bytes: Uint8Array): ChangeRecord | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    const rec = { changeEpoch: r.u64(), proposer: r.u64(), proposedAt: r.u64(), effectiveAt: r.u64(), operations: readChange(r) };
    r.end();
    return rec;
  });
}

/**
 * A change is pending while its record exists, it belongs to the current
 * change epoch and the key that proposed it is still one of the ID's keys
 * (`keyIds`: the key ids of the current key epoch).
 */
export function isPending(rec: ChangeRecord, core: Core, keyIds: bigint[]): boolean {
  return rec.changeEpoch === core.changeEpoch && keyIds.includes(rec.proposer);
}

export interface Proposal {
  approvals: bigint;
  reachedAt: bigint;
}

export function decodeProposal(bytes: Uint8Array | null): Proposal {
  if (!bytes) return { approvals: 0n, reachedAt: 0n };
  const r = new Reader(bytes);
  const p = { approvals: r.u64(), reachedAt: r.u64() };
  r.end();
  return p;
}

// ---- Operations and messages ---------------------------------------------------

/** `u64 key_id ‖ Bytes(witness)`: the witness of an ID account, and of registry operations. */
export function keyAuth(keyId: bigint | number, witness: Uint8Array): Uint8Array {
  return new Writer().u64(keyId).bytes(witness).finish();
}

/**
 * The message a key signs for a registry operation on `id`:
 * `Bytes(tag) ‖ chain ‖ registry ‖ id ‖ u64 key_id ‖ body`, where `key_id` is
 * the signing key's id within its own identity (a guardian's key for approvals
 * and withdrawals).
 */
export function registryMessage(chain: Uint8Array, registry: Uint8Array, id: Uint8Array, keyId: bigint | number, body: Uint8Array): Uint8Array {
  return new Writer().bytes(utf8(APP_TAG)).put(chain).put(registry).put(id).u64(keyId).put(body).finish();
}

/** Change numbers to cancel: `u64 count ‖ count × u64 change_no`, strictly increasing. */
export function encodeChangeNumbers(changeNos: bigint[]): Uint8Array {
  if (!strictlyIncreasing(changeNos)) throw new RangeError('change numbers must be strictly increasing');
  const w = new Writer().u64(changeNos.length);
  for (const n of changeNos) w.u64(n);
  return w.finish();
}

// Only a proposal carries the op nonce; a cancel, an approval and a withdrawal
// can each take effect only once by themselves.
export const proposeBody = (opNonce: bigint, change: Uint8Array) =>
  new Writer().u8(OP_PROPOSE).u64(opNonce).put(change).finish();
export const cancelBody = (changeNos: bigint[]) => new Writer().u8(OP_CANCEL).put(encodeChangeNumbers(changeNos)).finish();
/** Body of an approval (`op` = {@link OP_APPROVE}) or a withdrawal ({@link OP_WITHDRAW}): `op ‖ proposal ‖ guardian`. */
export const approvalBody = (op: number, proposal: Uint8Array, guardian: Uint8Array) =>
  new Writer().u8(op).put(proposal).put(guardian).finish();

export const createInput = (creation: Uint8Array) => new Writer().u8(OP_CREATE).put(creation).finish();
export const proposeInput = (id: Uint8Array, opNonce: bigint, auth: Uint8Array, change: Uint8Array) =>
  new Writer().u8(OP_PROPOSE).put(id).u64(opNonce).put(auth).put(change).finish();
/** Cancels every listed change that is still pending, skipping the others; at least one must be pending. */
export const cancelInput = (id: Uint8Array, auth: Uint8Array, changeNos: bigint[]) =>
  new Writer().u8(OP_CANCEL).put(id).put(auth).put(encodeChangeNumbers(changeNos)).finish();
export const applyInput = (id: Uint8Array, changeNo: bigint) => new Writer().u8(OP_APPLY).put(id).u64(changeNo).finish();
/**
 * A guardian approves recovering `id` to the keys of `keyList` (not empty). The
 * registry derives the proposal from the ID's current recovery epoch; the key
 * signs {@link approvalBody} with that proposal.
 */
export const approveInput = (id: Uint8Array, guardian: Uint8Array, keyList: Uint8Array, auth: Uint8Array) =>
  new Writer().u8(OP_APPROVE).put(id).put(guardian).put(keyList).put(auth).finish();
export const withdrawInput = (id: Uint8Array, proposal: Uint8Array, guardian: Uint8Array, auth: Uint8Array) =>
  new Writer().u8(OP_WITHDRAW).put(id).put(proposal).put(guardian).put(auth).finish();
export const recoverInput = (id: Uint8Array, keyList: Uint8Array) => new Writer().u8(OP_RECOVER).put(id).put(keyList).finish();
export const infoInput = (id: Uint8Array) => new Writer().u8(OP_INFO).put(id).finish();

// ---- Events ----------------------------------------------------------------------

export type IdEvent =
  | { kind: 'created'; id: Uint8Array }
  | { kind: 'changeProposed'; id: Uint8Array; changeNo: bigint; effectiveAt: bigint; operations: Operation[] }
  | { kind: 'changeCancelled'; id: Uint8Array; changeNo: bigint }
  | { kind: 'changeApplied'; id: Uint8Array; changeNo: bigint }
  | {
      kind: 'recoveryApproved';
      id: Uint8Array;
      proposal: Uint8Array;
      guardian: Uint8Array;
      approvals: bigint;
      reachedAt: bigint;
      /** The keys the recovery installs. */
      keys: IdKey[];
    }
  | { kind: 'recoveryWithdrawn'; id: Uint8Array; proposal: Uint8Array; guardian: Uint8Array; approvals: bigint; reachedAt: bigint }
  | { kind: 'recovered'; id: Uint8Array; keyEpoch: bigint };

export function decodeIdEvent(payload: Uint8Array): IdEvent | null {
  return tryDecode(() => {
    const r = new Reader(payload);
    const topic = r.array32();
    const id = r.array32();
    let e: IdEvent;
    if (equal(topic, CREATED_TOPIC)) e = { kind: 'created', id };
    else if (equal(topic, CHANGE_PROPOSED_TOPIC))
      e = { kind: 'changeProposed', id, changeNo: r.u64(), effectiveAt: r.u64(), operations: readChange(r) };
    else if (equal(topic, CHANGE_CANCELLED_TOPIC)) e = { kind: 'changeCancelled', id, changeNo: r.u64() };
    else if (equal(topic, CHANGE_APPLIED_TOPIC)) e = { kind: 'changeApplied', id, changeNo: r.u64() };
    else if (equal(topic, RECOVERY_APPROVED_TOPIC))
      e = { kind: 'recoveryApproved', id, proposal: r.array32(), guardian: r.array32(), approvals: r.u64(), reachedAt: r.u64(), keys: readKeyList(r) };
    else if (equal(topic, RECOVERY_WITHDRAWN_TOPIC))
      e = { kind: 'recoveryWithdrawn', id, proposal: r.array32(), guardian: r.array32(), approvals: r.u64(), reachedAt: r.u64() };
    else if (equal(topic, RECOVERED_TOPIC)) e = { kind: 'recovered', id, keyEpoch: r.u64() };
    else throw new RangeError('unknown topic');
    r.end();
    return e;
  });
}
