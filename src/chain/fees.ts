// Paying for an operation that cannot pay for itself: a registry operation,
// or anything from a time-locked account. The operation's calls go into a
// batch and, last, a zero transfer from the paying account that carries the
// fee. The payment is bound to exactly those calls (the coin's `within`
// condition), so the miner is paid only for including them.
import { policyAccount, signMessage } from './accounts';
import { BatchCall, batchInput, batchPrefix } from './batch';
import * as coin from './coin';
import type { Canonical } from './contracts';
import { keyAuth } from './identity';
import type { Scheme } from './keys';
import { BATCH_BUDGET, encodeCall } from './tx';

/** What signs for the paying account; the app's signers have this shape. */
export interface PayingSigner {
  key: { scheme: Scheme };
  secret: Uint8Array;
  /** For an identity account: which key of the identity signs. */
  keyId?: bigint;
  policy: Uint8Array;
  params: Uint8Array;
}

/** An instant account that pays `fee` of coin `coinId`. */
export interface FeePayer {
  signer: PayingSigner;
  coinId: Uint8Array;
  fee: bigint;
}

/** The account a signer acts for. */
export function signerAccount(signer: PayingSigner): Uint8Array {
  return policyAccount(signer.policy, signer.params);
}

/** A signer's witness for a message: the signature, with the key id for an identity account. */
export function accountWitness(signer: PayingSigner, message: Uint8Array): Uint8Array {
  const signature = signMessage(signer.key.scheme, signer.secret, message);
  return signer.keyId === undefined ? signature : keyAuth(signer.keyId, signature);
}

/**
 * The payment that goes last in a batch after `calls`: a transfer of 0 to the
 * payer's own account with the fee, valid only as a call of `c.batch` after
 * exactly `calls`, and only until `validUntil` (seconds), so that one whose
 * operation went in alone does not stay usable (wiki: programs/conventions).
 * Signed with the payer's account nonce `nonce`.
 */
export function feePayment(chain: Uint8Array, c: Canonical, calls: BatchCall[], payer: FeePayer, nonce: bigint, validUntil: bigint) {
  const account = signerAccount(payer.signer);
  const action: coin.Action = {
    effect: { kind: 'transfer', to: account, amount: 0n },
    timing: { fee: payer.fee, tip: 0n, notBefore: 0n, validUntil, within: { batch: c.batch, prefix: batchPrefix(calls) } },
  };
  const message = coin.coinMessage(chain, payer.coinId, account, nonce, action);
  const { policy, params } = payer.signer;
  const input = coin.executeInput({ policy, params, nonce, witness: accountWitness(payer.signer, message) }, action);
  const call: BatchCall = { target: payer.coinId, input };
  return { call, action, message, account };
}

/** The batch transaction of `calls` followed by the payer's bound fee payment. */
export function sponsoredCall(chain: Uint8Array, c: Canonical, calls: BatchCall[], payer: FeePayer, payerNonce: bigint, validUntil: bigint): Uint8Array {
  const payment = feePayment(chain, c, calls, payer, payerNonce, validUntil);
  return encodeCall(c.batch, batchInput([...calls, payment.call]), BATCH_BUDGET);
}
