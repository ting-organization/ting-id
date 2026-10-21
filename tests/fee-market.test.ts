// The automatic fee: what recent transactions paid per share of a block,
// times the share an operation takes.
import { describe, expect, it } from 'vitest';
import { Writer, concat, hex } from '../src/chain/bytes';
import { PRODUCER_PAYMENT_TOPIC, TRANSFER_TOPIC, ZERO_ACCOUNT } from '../src/chain/coin';
import { OPERATION_SHARE, PaidBlock, SAMPLE, feeFor, medianRate, payoutAccount, ratesOf, shareOf } from '../src/chain/feeMarket';

const LIMITS = [150_000_000, 1024, 262_144, 131_072];
const miner = new Uint8Array(32).fill(0x4d);
const other = new Uint8Array(32).fill(0x0e);
const daler = new Uint8Array(32).fill(0xd1);
const otherCoin = new Uint8Array(32).fill(0xc2);

/** Producer data naming `account` as the payout account, as ting-miner writes it. */
const producerData = (account: Uint8Array) => concat(new TextEncoder().encode('OCP1'), new Writer().u32(1).u32(32).put(account).finish());
const payment = (contract: Uint8Array, payee: Uint8Array, amount: bigint) => ({ contract, payload: new Writer().put(PRODUCER_PAYMENT_TOPIC).put(payee).u128(amount).finish() });
/** A payment that took a tenth of the block's compute and paid `fee`. */
const paidTx = (fee: bigint, payee = miner, contract = daler) => ({ status: 0, used: [15_000_000, 20, 829, 0], events: [payment(contract, payee, fee)] });
const block = (height: number, receipts: PaidBlock['receipts'], producer = producerData(miner)): PaidBlock => ({ height, producer, limits: LIMITS, receipts });

describe('the share of a block', () => {
  it('is the largest fraction of any capacity', () => {
    expect(shareOf([15_000_000, 20, 829, 0], LIMITS)).toBeCloseTo(0.1);
    expect(shareOf([1_000_000, 512, 0, 0], LIMITS)).toBeCloseTo(0.5);
  });
});

describe('the payout account in producer data', () => {
  it('is the first 32-byte entry of type 1 after "OCP1"', () => {
    expect(hex(payoutAccount(producerData(miner))!)).toBe(hex(miner));
    const two = concat(new TextEncoder().encode('OCP1'), new Writer().u32(7).u32(2).put(new Uint8Array(2)).u32(1).u32(32).put(other).finish());
    expect(hex(payoutAccount(two)!)).toBe(hex(other));
  });
  it('is none without the prefix or with a cut entry', () => {
    expect(payoutAccount(new Uint8Array(44))).toBeNull();
    expect(payoutAccount(producerData(miner).subarray(0, 30))).toBeNull();
  });
});

describe('recent rates', () => {
  it('count what paying transactions gave the payout account, per whole block', () => {
    const rates = ratesOf([block(10, [paidTx(1n), paidTx(3n)])]);
    expect(rates.map((r) => [r.coin, Math.round(r.perBlock)])).toEqual([
      [hex(daler), 10],
      [hex(daler), 30],
    ]);
  });

  it('leave out payments to others, failed transactions and blocks without a payout account', () => {
    const failed = { ...paidTx(5n), status: 1 };
    expect(ratesOf([block(10, [paidTx(5n, other), failed])])).toEqual([]);
    expect(ratesOf([block(10, [paidTx(5n)], new Uint8Array(0))])).toEqual([]);
  });

  it('take the median of the newest, per coin', () => {
    const blocks = [block(1, [paidTx(100n)]), ...Array.from({ length: SAMPLE }, (_, i) => block(2 + i, [paidTx(BigInt(1 + (i % 3)))])), block(99, [paidTx(9n, miner, otherCoin)])];
    const rates = ratesOf(blocks);
    // The oldest, 100 per tenth of a block, has fallen out of the sample.
    expect(medianRate(rates, hex(daler))).toBeCloseTo(20);
    expect(medianRate(rates, hex(otherCoin))).toBeCloseTo(90);
    expect(medianRate(rates, hex(new Uint8Array(32)))).toBeNull();
  });
});

describe('the fee of an operation', () => {
  it('is the rate times its share, to two significant digits', () => {
    // As on the network: payments paid 0.0001 DLR (10,000 units) for 15.6M of 150M compute.
    const rate = 10_000 / (15_600_000 / 150_000_000);
    expect(feeFor(rate, OPERATION_SHARE.own)).toBe(10_000n);
    expect(feeFor(rate, OPERATION_SHARE.bundled)).toBe(16_000n);
    expect(feeFor(rate, OPERATION_SHARE.run)).toBe(950n);
    expect(feeFor(10, OPERATION_SHARE.bundled)).toBe(2n);
  });

  it('pays what the last ones paid, so it does not creep up', () => {
    for (const start of [1n, 7n, 10_000n, 16_000n]) {
      let fee = start;
      for (let round = 0; round < 10; round++) fee = feeFor(Number(fee) / OPERATION_SHARE.own, OPERATION_SHARE.own);
      expect(fee).toBe(start);
    }
  });

  it('takes no number from a node on trust', () => {
    expect(shareOf([15_000_000, 20, 829, 0], [1e308 * 10, 1024, 262_144, 131_072])).toBeCloseTo(20 / 1024);
    expect(ratesOf([{ ...block(10, [paidTx(1n)]), limits: [0, 0, 0, 0] }])).toEqual([]);
    expect(feeFor(Infinity, OPERATION_SHARE.own)).toBe(1n);
  });

  it('is the smallest unit with no recent paying transaction, or a rate that rounds to nothing', () => {
    expect(feeFor(null, OPERATION_SHARE.own)).toBe(1n);
    expect(feeFor(10, OPERATION_SHARE.run)).toBe(1n);
  });
});

describe('a block reward claim', () => {
  it('is no fee: it pays the producer from coins it issues', () => {
    const transfer = (from: Uint8Array, to: Uint8Array, amount: bigint) => ({ contract: daler, payload: new Writer().put(TRANSFER_TOPIC).put(from).put(to).u128(amount).finish() });
    const claim = { status: 0, used: [1_422_091, 9, 432, 0], events: [transfer(ZERO_ACCOUNT, miner, 1_000_000n), payment(daler, miner, 1_000_000n)] };
    expect(ratesOf([block(10, [claim, paidTx(1n)])]).map((r) => Math.round(r.perBlock))).toEqual([10]);
  });
});
