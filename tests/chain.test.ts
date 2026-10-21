// Byte-exact interoperability with the Rust reference crates: every value in
// tests/vectors.json holds byte vectors from a Rust implementation of the
// same formats: the app must produce exactly those bytes.
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { Reader, Writer, hex, unhex, utf8 } from '../src/chain/bytes';
import { BATCH_QUERY, batchInput, batchPrefix, batchPrefixStart, decodeBatchResult } from '../src/chain/batch';
import { policyAccount, signMessage, signingDigest } from '../src/chain/accounts';
import { publicKey, verifyDigest } from '../src/chain/keys';
import { canonical } from '../src/chain/contracts';
import { BATCH_BUDGET, encodeCall, contractId, transactionId } from '../src/chain/tx';
import { FeePayer, feePayment, sponsoredCall } from '../src/chain/fees';
import * as coin from '../src/chain/coin';
import * as id from '../src/chain/identity';

const v = JSON.parse(readFileSync(new URL('./vectors.json', import.meta.url), 'utf8'));
const chain = unhex(v.chain_id);
const h = (b: Uint8Array) => hex(b);
const fill = (n: number) => new Uint8Array(32).fill(n);

describe('canonical contracts', () => {
  it('derives the same ids as the wallet CLI', () => {
    const c = canonical(chain);
    expect(h(c.policySecp256k1)).toBe(v.canonical.policy_secp256k1);
    expect(h(c.policyP256)).toBe(v.canonical.policy_p256);
    expect(h(c.multisig)).toBe(v.canonical.policy_multisig_secp256k1);
    expect(h(c.registry)).toBe(v.canonical.id_registry);
    expect(h(c.batch)).toBe(v.canonical.batch);
  });
});

describe('keys and signatures', () => {
  const secret = new Uint8Array(32).fill(1);
  it('secp256k1 matches k256 (RFC 6979, low S)', () => {
    const c = canonical(chain);
    const pub = publicKey('secp256k1', secret);
    expect(h(pub)).toBe(v.signing.secp256k1.pubkey);
    expect(h(policyAccount(c.policySecp256k1, pub))).toBe(v.signing.secp256k1.account);
    const message = unhex(v.signing.message);
    expect(h(signingDigest(message))).toBe(v.signing.digest);
    const sig = signMessage('secp256k1', secret, message);
    expect(h(sig)).toBe(v.signing.secp256k1.signature);
    expect(verifyDigest('secp256k1', pub, signingDigest(message), sig)).toBe(true);
  });
  it('p256 matches the p256 crate', () => {
    const c = canonical(chain);
    const pub = publicKey('p256', secret);
    expect(h(pub)).toBe(v.signing.p256.pubkey);
    expect(h(policyAccount(c.policyP256, pub))).toBe(v.signing.p256.account);
    expect(h(signMessage('p256', secret, unhex(v.signing.message)))).toBe(v.signing.p256.signature);
  });
});

describe('coin formats', () => {
  const secret = new Uint8Array(32).fill(1);
  const c = canonical(chain);
  const pub = publicKey('secp256k1', secret);
  const account = policyAccount(c.policySecp256k1, pub);
  const transfer: coin.Action = {
    effect: { kind: 'transfer', to: fill(2), amount: 12_345n },
    timing: { fee: 1n, tip: 2n, notBefore: 3n, validUntil: 4n, within: null },
  };
  const roundTrip = (a: coin.Action) => {
    const w = new Writer();
    coin.encodeAction(w, a);
    const bytes = w.finish();
    const r = new Reader(bytes);
    const back = coin.readAction(r);
    r.end();
    return { bytes, back };
  };
  it('signs and encodes an execute call', () => {
    const message = coin.coinMessage(chain, unhex(v.coin.coin), account, 7n, transfer);
    expect(h(message)).toBe(v.coin.message);
    const witness = signMessage('secp256k1', secret, message);
    const input = coin.executeInput({ policy: c.policySecp256k1, params: pub, nonce: 7n, witness }, transfer);
    expect(h(input)).toBe(v.coin.execute);
  });
  it('ends the timing with its condition: 00 for none, 01 ‖ batch ‖ prefix', () => {
    const plain = roundTrip(transfer);
    expect(plain.back).toEqual(transfer);
    expect(plain.bytes[plain.bytes.length - 1]).toBe(0);
    const sponsored: coin.Action = { ...transfer, timing: { ...transfer.timing, within: { batch: fill(10), prefix: fill(11) } } };
    const conditioned = roundTrip(sponsored);
    expect(conditioned.back).toEqual(sponsored);
    expect(h(conditioned.bytes.subarray(plain.bytes.length - 1))).toBe(`01${h(fill(10))}${h(fill(11))}`);
    expect(h(conditioned.bytes.subarray(0, plain.bytes.length - 1))).toBe(h(plain.bytes.subarray(0, plain.bytes.length - 1)));
  });
  it('signs and encodes a cancel of several actions, without a nonce', () => {
    const cancel: coin.Cancel = { sequences: [1n, 7n], fee: 5n, validUntil: 9n };
    const message = coin.cancelMessage(chain, unhex(v.coin.coin), account, cancel);
    expect(h(message)).toBe(v.coin.cancel_message);
    const witness = signMessage('secp256k1', secret, message);
    expect(h(coin.cancelInput({ policy: c.policySecp256k1, params: pub, witness }, cancel))).toBe(v.coin.cancel);
    expect(h(coin.cancelInput('contract', cancel))).toBe(v.coin.contract_cancel);
    const w = new Writer();
    coin.encodeCancel(w, cancel);
    expect(coin.readCancel(new Reader(w.finish()))).toEqual(cancel);
    // Sequences must be strictly increasing: never repeated, never descending.
    expect(() => coin.cancelInput('contract', { sequences: [4n, 4n], fee: 0n, validUntil: 0n })).toThrow();
    expect(() => coin.cancelInput('contract', { sequences: [4n, 1n], fee: 0n, validUntil: 0n })).toThrow();
  });
  it('decodes records and events', () => {
    expect(coin.decodeAccount(unhex(v.coin.account_record))).toEqual({ balance: 1000n, locked: 250n, nonce: 3n });
    const config = coin.decodeConfig(unhex(v.coin.config_record))!;
    expect(config.name).toBe('Test Coin');
    expect(config.symbol).toBe('TST');
    expect(config.decimals).toBe(2);
    expect(config.halvingInterval).toBe(1000n);
    expect(config.deployHeight).toBe(42n);
    expect(h(coin.scheduledKey(account, 7n))).toBe(v.coin.scheduled_key);
    const scheduled = coin.decodeScheduled(unhex(v.coin.scheduled_record))!;
    expect(scheduled.executeAt).toBe(99n);
    expect(scheduled.reserved).toBe(12_347n);
    expect(scheduled.tip).toBe(2n);
    expect(scheduled.effect).toEqual({ kind: 'transfer', to: fill(2), amount: 12_345n });
    expect(coin.decodeCoinEvent(unhex(v.coin.transfer_event))).toEqual({
      kind: 'transfer',
      from: account,
      to: fill(2),
      amount: 12_345n,
    });
    expect(coin.decodeCoinEvent(unhex(v.coin.scheduled_event))).toEqual({
      kind: 'scheduled',
      account,
      sequence: 7n,
      executeAt: 99n,
      tip: 2n,
      effect: { kind: 'transfer', to: fill(2), amount: 12_345n },
    });
    expect(coin.decodeCoinEvent(unhex(v.coin.executed_event))).toEqual({ kind: 'executed', account, sequence: 7n });
    expect(coin.decodeCoinEvent(unhex(v.coin.cancelled_event))).toEqual({ kind: 'cancelled', account, sequence: 7n });
    expect(coin.decodeCoinEvent(unhex(v.coin.producer_payment))).toEqual({ kind: 'producerPayment', payee: fill(8), amount: 1n });
    expect(coin.decodeCoinEvent(utf8('junk'))).toBeNull();
  });
});

describe('identity formats', () => {
  const secret = new Uint8Array(32).fill(1);
  const c = canonical(chain);
  const keys: id.IdKey[] = [
    { policy: c.policyP256, params: publicKey('p256', secret) },
    { policy: c.policySecp256k1, params: publicKey('secp256k1', secret) },
  ];
  const settings: id.Settings = {
    keys,
    guardians: [fill(5), fill(6)],
    threshold: 0,
    changeDelay: 604_800,
    recoveryDelay: 172_800,
    slots: [0, 604_800],
    salt: fill(9),
  };
  const creation = id.encodeCreation(settings);
  const identity = id.idOf(creation);
  const keyList = id.encodeKeyList(keys.slice(1));
  it('creates the same id and accounts', () => {
    expect(h(creation)).toBe(v.identity.creation);
    expect(h(identity)).toBe(v.identity.id);
    expect(h(id.createInput(creation))).toBe(v.identity.create_input);
    expect(h(id.account(c.registry, identity, 1))).toBe(v.identity.account_slot_1);
    expect(id.decodeCreation(creation)).toEqual(settings);
    expect(id.validSettings(settings, identity)).toBeNull();
  });
  it('lists guardians in strictly increasing byte order, never the id', () => {
    const unsorted = { ...settings, guardians: [fill(6), fill(5)] };
    expect(id.validSettings(unsorted, identity)).toBe('guardians must be sorted');
    expect(id.validSettings({ ...settings, guardians: [fill(5), fill(5)] }, identity)).toBe('guardians must be distinct');
    expect(id.validSettings({ ...settings, guardians: [identity] }, identity)).toBe('an identity cannot guard itself');
    const sorted = { ...unsorted, guardians: id.sortGuardians(unsorted.guardians) };
    expect(h(id.encodeCreation(sorted))).toBe(v.identity.creation);
  });
  it('derives record keys', () => {
    expect(h(id.coreKey(identity))).toBe(v.identity.core_key);
    expect(h(id.keyKey(identity, 0, 1))).toBe(v.identity.key_key_0_1);
    expect(h(id.guardianKey(identity, fill(5)))).toBe(v.identity.guardian_key_5);
    expect(h(id.slotKey(identity, 1))).toBe(v.identity.slot_key_1);
    expect(h(id.changeKey(identity, 2))).toBe(v.identity.change_key_2);
    expect(h(id.proposalKey(identity, fill(4)))).toBe(v.identity.proposal_key);
    expect(h(id.approvalKey(identity, fill(4), fill(5)))).toBe(v.identity.approval_key);
    expect(h(id.currentApprovalKey(identity, 3, fill(5)))).toBe(v.identity.current_approval_key_3);
  });
  it('decodes core and key records', () => {
    const core = id.decodeCore(unhex(v.identity.core_record))!;
    expect(core.nextKeyId).toBe(5n);
    expect(core.guardianCount).toBe(3n);
    expect(id.required(core)).toBe(2n);
    expect(h(id.encodeCore(core))).toBe(v.identity.core_record);
    expect(id.decodeKeyRecord(unhex(v.identity.key_record))).toEqual(keys[1]);
  });
  it('encodes changes and proposals exactly', () => {
    const ops: id.Operation[] = [
      { kind: 'addKey', key: keys[1] },
      { kind: 'removeKey', keyId: 3n },
      { kind: 'addGuardian', id: fill(7) },
      { kind: 'setSlot', slot: 1n, delay: 99n },
    ];
    const change = id.encodeChange(ops);
    expect(h(change)).toBe(v.identity.change);
    expect(id.decodeChange(change)).toEqual(ops);
    const rec = id.decodeChangeRecord(unhex(v.identity.change_record))!;
    expect(rec.changeEpoch).toBe(2n);
    expect(rec.proposer).toBe(1n);
    expect(rec.proposedAt).toBe(1_699_000_000n);
    expect(rec.effectiveAt).toBe(1_700_000_000n);
    expect(rec.operations).toEqual(ops);
    // Pending in the current change epoch while the proposing key exists.
    const core = id.decodeCore(unhex(v.identity.core_record))!;
    expect(id.isPending(rec, core, [0n, 1n])).toBe(true);
    expect(id.isPending(rec, core, [0n, 2n])).toBe(false);
    expect(id.isPending(rec, { ...core, changeEpoch: 3n }, [0n, 1n])).toBe(false);
    const body = id.proposeBody(4n, change);
    const message = id.registryMessage(chain, c.registry, identity, 1n, body);
    expect(h(message)).toBe(v.identity.propose_message);
    const auth = id.keyAuth(1n, signMessage('secp256k1', secret, message));
    expect(h(id.proposeInput(identity, 4n, auth, change))).toBe(v.identity.propose_input);
    expect(h(keyList)).toBe(v.identity.key_list);
    expect(h(id.proposalHash(identity, 2n, keyList))).toBe(v.identity.proposal_hash);
    expect(h(id.keyAuth(0n, utf8('sig')))).toBe(v.identity.key_auth);
  });
  it('cancels several changes with one signature, without a nonce', () => {
    const message = id.registryMessage(chain, c.registry, identity, 1n, id.cancelBody([2n, 5n]));
    expect(h(message)).toBe(v.identity.cancel_message);
    const auth = id.keyAuth(1n, signMessage('secp256k1', secret, message));
    expect(h(id.cancelInput(identity, auth, [2n, 5n]))).toBe(v.identity.cancel_input);
    expect(() => id.cancelBody([5n, 2n])).toThrow();
  });
  it('approves a recovery with the key list', () => {
    const proposal = id.proposalHash(identity, 3n, keyList);
    // Signed by the guardian's key 0, named in the message.
    const message = id.registryMessage(chain, c.registry, identity, 0n, id.approvalBody(id.OP_APPROVE, proposal, fill(5)));
    expect(h(message)).toBe(v.identity.approve_message);
    const auth = id.keyAuth(0n, signMessage('secp256k1', secret, message));
    expect(h(id.approveInput(identity, fill(5), keyList, auth))).toBe(v.identity.approve_input);
  });
  it('decodes events', () => {
    expect(id.decodeIdEvent(unhex(v.identity.events.created))).toEqual({ kind: 'created', id: identity });
    const proposed = id.decodeIdEvent(unhex(v.identity.events.change_proposed));
    expect(proposed?.kind).toBe('changeProposed');
    expect(id.decodeIdEvent(unhex(v.identity.events.recovery_approved))).toEqual({
      kind: 'recoveryApproved',
      id: identity,
      proposal: fill(4),
      guardian: fill(5),
      approvals: 2n,
      reachedAt: 1_700_000_000n,
      keys: keys.slice(1),
    });
    expect(id.decodeIdEvent(unhex(v.identity.events.recovery_withdrawn))).toEqual({
      kind: 'recoveryWithdrawn',
      id: identity,
      proposal: fill(4),
      guardian: fill(5),
      approvals: 1n,
      reachedAt: 0n,
    });
    expect(id.decodeIdEvent(unhex(v.identity.events.recovered))).toEqual({ kind: 'recovered', id: identity, keyEpoch: 1n });
  });
});

describe('batch', () => {
  it('encodes calls back to back and decodes results', () => {
    const input = batchInput([
      { target: fill(7), input: utf8('abc') },
      { target: fill(8), input: new Uint8Array() },
    ]);
    expect(h(input)).toBe(`${h(fill(7))}03000000616263${h(fill(8))}00000000`);
    expect(h(batchInput([]))).toBe('');
    expect(decodeBatchResult(unhex('0100000061' + '00000000'))).toEqual([utf8('a'), new Uint8Array()]);
    expect(h(BATCH_QUERY)).toBe('00');
    expect(decodeBatchResult(unhex('05000000'))).toBeNull();
  });
  it('binds a sponsoring payment to the calls before it', () => {
    const secret = new Uint8Array(32).fill(1);
    const c = canonical(chain);
    const pub = publicKey('secp256k1', secret);
    const account = policyAccount(c.policySecp256k1, pub);
    const coinId = unhex(v.coin.coin);
    const first = { target: coinId, input: unhex(v.coin.execute) };
    expect(h(batchPrefixStart())).toBe(v.batch.prefix_start);
    const prefix = batchPrefix([first]);
    expect(h(prefix)).toBe(v.batch.prefix_1);
    // A zero transfer that carries the fee, valid only as the batch's second call.
    const bound: coin.Action = {
      effect: { kind: 'transfer', to: account, amount: 0n },
      timing: { fee: 9n, tip: 0n, notBefore: 0n, validUntil: 0n, within: { batch: c.batch, prefix } },
    };
    const message = coin.coinMessage(chain, coinId, account, 8n, bound);
    expect(h(message)).toBe(v.coin.bound_message);
    const witness = signMessage('secp256k1', secret, message);
    const execute = coin.executeInput({ policy: c.policySecp256k1, params: pub, nonce: 8n, witness }, bound);
    expect(h(execute)).toBe(v.coin.bound_execute);
    // The sponsoring payment goes last, after the calls it commits to.
    expect(h(batchInput([first, { target: coinId, input: execute }]))).toBe(v.batch.input);
  });
  it('builds a sponsored call: the operation, then the bound payment', () => {
    const secret = new Uint8Array(32).fill(1);
    const c = canonical(chain);
    const pub = publicKey('secp256k1', secret);
    const coinId = unhex(v.coin.coin);
    const operation = { target: coinId, input: unhex(v.coin.execute) };
    const payer: FeePayer = { signer: { key: { scheme: 'secp256k1' }, secret, policy: c.policySecp256k1, params: pub }, coinId, fee: 9n };
    const tx = sponsoredCall(chain, c, [operation], payer, 8n, 0n);
    expect(h(tx)).toBe(h(encodeCall(c.batch, unhex(v.batch.input), BATCH_BUDGET)));
    const payment = feePayment(chain, c, [operation], payer, 8n, 0n);
    expect(h(payment.message)).toBe(v.coin.bound_message);
    expect(h(payment.call.input)).toBe(v.coin.bound_execute);
    expect(payment.action.timing.within).toEqual({ batch: c.batch, prefix: batchPrefix([operation]) });
    expect(payment.action.timing.fee).toBe(9n);
    // A payment expires, so one whose operation went in alone does not stay usable.
    expect(feePayment(chain, c, [operation], payer, 8n, 1_790_000_000n).action.timing.validUntil).toBe(1_790_000_000n);
    // Last: the batch input ends with the payment call.
    const input = unhex(v.batch.input);
    const tail = batchInput([payment.call]);
    expect(h(input.subarray(input.length - tail.length))).toBe(h(tail));
    expect(h(input.subarray(0, input.length - tail.length))).toBe(h(batchInput([operation])));
  });
});

describe('transactions', () => {
  it('encodes calls and derives ids', () => {
    const tx = encodeCall(fill(7), utf8('abc'), [1n, 2n, 3n, 4n]);
    expect(h(tx)).toBe(v.transaction.call);
    expect(h(transactionId(chain, tx))).toBe(v.transaction.call_id);
    expect(h(contractId(chain, tx))).toBe(v.transaction.contract_ordinal_0);
  });
});
