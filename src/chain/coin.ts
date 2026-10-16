// The coin reference program (wiki: programs/coin): records, actions, cancels,
// messages and events (wiki: programs/coin).
import { Reader, Writer, equal, filled, fromUtf8, strictlyIncreasing, tryDecode, u64le, utf8 } from './bytes';
import { taggedHash } from './hash';

export const APP_TAG = 'app/coin-v1';
export const VERSION = 1;
export const CONFIG_KEY = filled(0);
export const STATE_KEY = (() => {
  const k = filled(0);
  k[31] = 1;
  return k;
})();
export const ZERO_ACCOUNT = filled(0);
export const UNCAPPED = (1n << 128n) - 1n;

export const OP_BALANCE = 0x01;
export const OP_INFO = 0x02;
export const OP_CLAIM = 0x03;
export const OP_RUN = 0x04;
export const OP_SCHEDULED = 0x05;
export const OP_EXECUTE = 0x10;
export const OP_CANCEL = 0x11;

const AUTH_POLICY = 0;
const AUTH_CONTRACT = 1;
const ACT_TRANSFER = 1;
const ACT_BURN = 2;
const ACT_MINT = 3;
const ACT_SET_MINTER = 4;
const WITHIN_NONE = 0;
const WITHIN_BATCH = 1;

export const TRANSFER_TOPIC = taggedHash('app/coin-v1/event/transfer');
export const MINTER_TOPIC = taggedHash('app/coin-v1/event/minter');
export const SCHEDULED_TOPIC = taggedHash('app/coin-v1/event/scheduled');
export const EXECUTED_TOPIC = taggedHash('app/coin-v1/event/executed');
export const CANCELLED_TOPIC = taggedHash('app/coin-v1/event/cancelled');
export const PRODUCER_PAYMENT_TOPIC = taggedHash('app/event/producer-payment-v1');

export function isReserved(key: Uint8Array): boolean {
  return equal(key, CONFIG_KEY) || equal(key, STATE_KEY);
}

export function scheduledKey(account: Uint8Array, sequence: bigint | number): Uint8Array {
  return taggedHash('app/coin-v1/scheduled', account, u64le(sequence));
}

// ---- Deployment ---------------------------------------------------------------

/** A new coin's options, fixed at deployment except the minter. */
export interface CoinOptions {
  name: string;
  symbol: string;
  decimals: number;
  /** `UNCAPPED` for no cap. */
  maxSupply: bigint;
  initialReward: bigint;
  /** Blocks per era; 0 for a constant reward. */
  halvingInterval: bigint;
  tailReward: bigint;
  /** `ZERO_ACCOUNT` for none. */
  minter: Uint8Array;
  allocations: { account: Uint8Array; amount: bigint }[];
}

/**
 * The deployment input: `u8 version ‖ Bytes(name) ‖ Bytes(symbol) ‖ u8 decimals ‖
 * u128 max_supply ‖ u128 initial_reward ‖ u64 halving_interval ‖ u128 tail_reward ‖
 * minter(32) ‖ u64 count ‖ count × (account(32) ‖ u128 amount)`.
 */
export function encodeDeployInput(o: CoinOptions): Uint8Array {
  const w = new Writer()
    .u8(VERSION)
    .bytes(utf8(o.name))
    .bytes(utf8(o.symbol))
    .u8(o.decimals)
    .u128(o.maxSupply)
    .u128(o.initialReward)
    .u64(o.halvingInterval)
    .u128(o.tailReward)
    .put(o.minter)
    .u64(o.allocations.length);
  for (const a of o.allocations) w.put(a.account).u128(a.amount);
  return w.finish();
}

/** Why the coin would revert `bad deploy input` with these options, or null. */
export function deployProblem(o: CoinOptions): string | null {
  if (!o.name) return 'the name is empty';
  if (!o.symbol) return 'the symbol is empty';
  if (new RegExp(HIDDEN).test(o.name + o.symbol)) return 'the name and symbol cannot hold invisible or direction characters';
  if (!Number.isInteger(o.decimals) || o.decimals < 0 || o.decimals > 255) return 'decimals must be 0 to 255';
  if (o.minter.length !== 32 || equal(o.minter, STATE_KEY)) return 'the minter is not an account';
  if (o.tailReward > o.initialReward) return 'the tail reward exceeds the block reward';
  let total = 0n;
  for (const a of o.allocations) {
    if (a.account.length !== 32 || isReserved(a.account)) return 'an allocation names a reserved key';
    total += a.amount;
  }
  if (total > o.maxSupply) return 'the allocations exceed the supply cap';
  return null;
}

/** The reward a claim at `height` mints: halved each era, at least the tail, within the cap. */
export function blockReward(config: CoinConfig, issued: bigint, height: bigint): bigint {
  if (height <= config.deployHeight) return 0n;
  const era = config.halvingInterval === 0n ? 0n : (height - config.deployHeight - 1n) / config.halvingInterval;
  const halved = era >= 128n ? 0n : config.initialReward >> era;
  const reward = halved > config.tailReward ? halved : config.tailReward;
  const room = config.maxSupply > issued ? config.maxSupply - issued : 0n;
  return reward < room ? reward : room;
}

// ---- Actions ---------------------------------------------------------------

export type Effect =
  | { kind: 'transfer'; to: Uint8Array; amount: bigint }
  | { kind: 'burn'; amount: bigint }
  | { kind: 'mint'; to: Uint8Array; amount: bigint }
  | { kind: 'setMinter'; minter: Uint8Array };

/**
 * A condition on where an action may run: inside the given batch deployment,
 * after exactly the calls whose progress prefix is `prefix`. A payment that
 * sponsors other calls of a batch carries it, so it cannot be taken out of the
 * batch and included alone.
 */
export interface Within {
  batch: Uint8Array;
  prefix: Uint8Array;
}

/**
 * Fee, tip, time window and condition of an action. A delayed account (a
 * time-locked slot, or a key whose policy answers with a delay) must use fee
 * 0: nothing leaves it at once. Its tip is locked and paid when the action runs.
 */
export interface Timing {
  fee: bigint;
  tip: bigint;
  /** Earliest execution (block timestamp); 0 for none. */
  notBefore: bigint;
  /** Inclusion deadline (block timestamp); 0 for never. */
  validUntil: bigint;
  /** `null` for no condition, the case of every ordinary action. */
  within: Within | null;
}

/** An authorized action (op `10`): what it does and when. */
export interface Action {
  effect: Effect;
  timing: Timing;
}

export const NO_TIMING: Timing = { fee: 0n, tip: 0n, notBefore: 0n, validUntil: 0n, within: null };

/** What executing the effect takes from the acting account. */
export function effectDebit(e: Effect): bigint {
  return e.kind === 'transfer' || e.kind === 'burn' ? e.amount : 0n;
}

export function encodeEffect(w: Writer, e: Effect): void {
  switch (e.kind) {
    case 'transfer':
      w.u8(ACT_TRANSFER).put(e.to).u128(e.amount);
      break;
    case 'burn':
      w.u8(ACT_BURN).u128(e.amount);
      break;
    case 'mint':
      w.u8(ACT_MINT).put(e.to).u128(e.amount);
      break;
    case 'setMinter':
      w.u8(ACT_SET_MINTER).put(e.minter);
      break;
  }
}

export function readEffect(r: Reader): Effect {
  switch (r.u8()) {
    case ACT_TRANSFER:
      return { kind: 'transfer', to: r.array32(), amount: r.u128() };
    case ACT_BURN:
      return { kind: 'burn', amount: r.u128() };
    case ACT_MINT:
      return { kind: 'mint', to: r.array32(), amount: r.u128() };
    case ACT_SET_MINTER:
      return { kind: 'setMinter', minter: r.array32() };
    default:
      throw new RangeError('unknown effect');
  }
}

/** `00` (no condition) or `01 ‖ batch(32) ‖ prefix(32)`. */
function encodeWithin(w: Writer, within: Within | null): void {
  if (!within) {
    w.u8(WITHIN_NONE);
    return;
  }
  if (within.batch.length !== 32 || within.prefix.length !== 32) throw new RangeError('batch and prefix are 32 bytes');
  w.u8(WITHIN_BATCH).put(within.batch).put(within.prefix);
}

function readWithin(r: Reader): Within | null {
  switch (r.u8()) {
    case WITHIN_NONE:
      return null;
    case WITHIN_BATCH:
      return { batch: r.array32(), prefix: r.array32() };
    default:
      throw new RangeError('unknown condition');
  }
}

/** `effect ‖ u128 fee ‖ u128 tip ‖ u64 not_before ‖ u64 valid_until ‖ within`. */
export function encodeAction(w: Writer, a: Action): void {
  encodeEffect(w, a.effect);
  w.u128(a.timing.fee).u128(a.timing.tip).u64(a.timing.notBefore).u64(a.timing.validUntil);
  encodeWithin(w, a.timing.within);
}

export function readAction(r: Reader): Action {
  const effect = readEffect(r);
  const timing = { fee: r.u128(), tip: r.u128(), notBefore: r.u64(), validUntil: r.u64(), within: readWithin(r) };
  return { effect, timing };
}

function messageHead(chain: Uint8Array, coin: Uint8Array, account: Uint8Array): Writer {
  return new Writer().bytes(utf8(APP_TAG)).put(chain).put(coin).put(account);
}

/** The message a policy account authorizes for an action: `Bytes(tag) ‖ chain ‖ coin ‖ account ‖ 10 ‖ u64 nonce ‖ action`. */
export function coinMessage(
  chain: Uint8Array,
  coin: Uint8Array,
  account: Uint8Array,
  nonce: bigint | number,
  action: Action,
): Uint8Array {
  const w = messageHead(chain, coin, account).u8(OP_EXECUTE).u64(nonce);
  encodeAction(w, action);
  return w.finish();
}

export interface PolicyAuth {
  policy: Uint8Array;
  params: Uint8Array;
  nonce: bigint | number;
  witness: Uint8Array;
}

/** Full `execute` input (op 10): a policy account with its nonce, or the calling contract's account. */
export function executeInput(auth: PolicyAuth | 'contract', action: Action): Uint8Array {
  const w = new Writer().u8(OP_EXECUTE);
  if (auth === 'contract') {
    w.u8(AUTH_CONTRACT);
  } else {
    w.u8(AUTH_POLICY).put(auth.policy).bytes(auth.params).u64(auth.nonce).bytes(auth.witness);
  }
  encodeAction(w, action);
  return w.finish();
}

// ---- Cancels -----------------------------------------------------------------

/**
 * Scheduled actions to cancel (op `11`) by sequence, strictly increasing. One
 * authorization covers them all; it carries no nonce and leaves the account's
 * nonce alone. Actions that already ran or were cancelled are skipped, but at
 * least one must still be scheduled. A delayed account must use fee 0.
 */
export interface Cancel {
  sequences: bigint[];
  fee: bigint;
  /** Inclusion deadline (block timestamp); 0 for never. */
  validUntil: bigint;
}

/** `u64 count ‖ count × u64 sequence ‖ u128 fee ‖ u64 valid_until`. */
export function encodeCancel(w: Writer, c: Cancel): void {
  if (!strictlyIncreasing(c.sequences)) throw new RangeError('sequences must be strictly increasing');
  w.u64(c.sequences.length);
  for (const s of c.sequences) w.u64(s);
  w.u128(c.fee).u64(c.validUntil);
}

export function readCancel(r: Reader): Cancel {
  const count = r.u64n();
  const sequences: bigint[] = [];
  for (let i = 0; i < count; i++) sequences.push(r.u64());
  if (!strictlyIncreasing(sequences)) throw new RangeError('sequences must be strictly increasing');
  return { sequences, fee: r.u128(), validUntil: r.u64() };
}

/** The message a policy account authorizes for a cancel: `Bytes(tag) ‖ chain ‖ coin ‖ account ‖ 11 ‖ cancel`. */
export function cancelMessage(chain: Uint8Array, coin: Uint8Array, account: Uint8Array, cancel: Cancel): Uint8Array {
  const w = messageHead(chain, coin, account).u8(OP_CANCEL);
  encodeCancel(w, cancel);
  return w.finish();
}

/** Authorization of a cancel: a policy account's, without a nonce. */
export interface CancelAuth {
  policy: Uint8Array;
  params: Uint8Array;
  witness: Uint8Array;
}

/** Full `cancel` input (op 11): a policy account, or the calling contract's account. */
export function cancelInput(auth: CancelAuth | 'contract', cancel: Cancel): Uint8Array {
  const w = new Writer().u8(OP_CANCEL);
  if (auth === 'contract') {
    w.u8(AUTH_CONTRACT);
  } else {
    w.u8(AUTH_POLICY).put(auth.policy).bytes(auth.params).bytes(auth.witness);
  }
  encodeCancel(w, cancel);
  return w.finish();
}

/** `run` and `scheduled` inputs: `op ‖ account ‖ u64 sequence`. */
export function targetInput(op: number, account: Uint8Array, sequence: bigint | number): Uint8Array {
  return new Writer().u8(op).put(account).u64(sequence).finish();
}

// ---- Records -----------------------------------------------------------------

export interface AccountRecord {
  balance: bigint;
  locked: bigint;
  nonce: bigint;
}

export const EMPTY_ACCOUNT: AccountRecord = { balance: 0n, locked: 0n, nonce: 0n };

export function available(a: AccountRecord): bigint {
  return a.balance > a.locked ? a.balance - a.locked : 0n;
}

export function decodeAccount(bytes: Uint8Array | null): AccountRecord {
  if (!bytes) return EMPTY_ACCOUNT;
  const r = new Reader(bytes);
  const out = { balance: r.u128(), locked: r.u128(), nonce: r.u64() };
  r.end();
  return out;
}

export interface CoinConfig {
  name: string;
  symbol: string;
  decimals: number;
  maxSupply: bigint;
  initialReward: bigint;
  halvingInterval: bigint;
  tailReward: bigint;
  deployHeight: bigint;
}

/**
 * Characters that change how the text around them reads, or do not show:
 * controls, direction overrides and isolates, zero-width and other format
 * characters. With them, one coin's name could pass for another's.
 */
const HIDDEN = '[\u0000-\u001f\u007f-\u009f\u00ad\u061c\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff\ufff9-\ufffb]';

/** A coin's name or symbol as shown: a hidden character shows as �. */
export function visibleLabel(text: string): string {
  return text.replace(new RegExp(HIDDEN, 'g'), '�');
}

export function decodeConfig(bytes: Uint8Array): CoinConfig | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    if (r.u8() !== VERSION) throw new RangeError('version');
    const c: CoinConfig = {
      name: visibleLabel(fromUtf8(r.bytes())),
      symbol: visibleLabel(fromUtf8(r.bytes())),
      decimals: r.u8(),
      maxSupply: r.u128(),
      initialReward: r.u128(),
      halvingInterval: r.u64(),
      tailReward: r.u128(),
      deployHeight: r.u64(),
    };
    r.end();
    return c;
  });
}

export interface CoinState {
  issued: bigint;
  burned: bigint;
  lastClaimed: bigint;
  minter: Uint8Array;
}

export function decodeState(bytes: Uint8Array): CoinState | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    const s = { issued: r.u128(), burned: r.u128(), lastClaimed: r.u64(), minter: r.array32() };
    r.end();
    return s;
  });
}

export interface ScheduledRecord {
  executeAt: bigint;
  reserved: bigint;
  tip: bigint;
  effect: Effect;
}

export function decodeScheduled(bytes: Uint8Array): ScheduledRecord | null {
  return tryDecode(() => {
    const r = new Reader(bytes);
    const s = { executeAt: r.u64(), reserved: r.u128(), tip: r.u128(), effect: readEffect(r) };
    r.end();
    return s;
  });
}

// ---- Events ----------------------------------------------------------------------

export type CoinEvent =
  | { kind: 'transfer'; from: Uint8Array; to: Uint8Array; amount: bigint }
  | { kind: 'minter'; minter: Uint8Array }
  | { kind: 'scheduled'; account: Uint8Array; sequence: bigint; executeAt: bigint; tip: bigint; effect: Effect }
  /** A scheduled action ran; its lock is released. */
  | { kind: 'executed'; account: Uint8Array; sequence: bigint }
  | { kind: 'cancelled'; account: Uint8Array; sequence: bigint }
  | { kind: 'producerPayment'; payee: Uint8Array; amount: bigint };

export function decodeCoinEvent(payload: Uint8Array): CoinEvent | null {
  return tryDecode(() => {
    const r = new Reader(payload);
    const topic = r.array32();
    let e: CoinEvent;
    if (equal(topic, TRANSFER_TOPIC)) {
      e = { kind: 'transfer', from: r.array32(), to: r.array32(), amount: r.u128() };
    } else if (equal(topic, MINTER_TOPIC)) {
      e = { kind: 'minter', minter: r.array32() };
    } else if (equal(topic, SCHEDULED_TOPIC)) {
      e = { kind: 'scheduled', account: r.array32(), sequence: r.u64(), executeAt: r.u64(), tip: r.u128(), effect: readEffect(r) };
    } else if (equal(topic, EXECUTED_TOPIC)) {
      e = { kind: 'executed', account: r.array32(), sequence: r.u64() };
    } else if (equal(topic, CANCELLED_TOPIC)) {
      e = { kind: 'cancelled', account: r.array32(), sequence: r.u64() };
    } else if (equal(topic, PRODUCER_PAYMENT_TOPIC)) {
      e = { kind: 'producerPayment', payee: r.array32(), amount: r.u128() };
    } else {
      throw new RangeError('unknown topic');
    }
    r.end();
    return e;
  });
}
