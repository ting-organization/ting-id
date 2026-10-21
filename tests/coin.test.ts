// A coin's deployment input and its rules (wiki: programs/coin, deployment).
// The production network's Daler is the vector: its options, deployed with the
// canonical budget, give the ContractId the chain shows for it.
import { describe, expect, it } from 'vitest';
import { hex, unhex } from '../src/chain/bytes';
import * as coin from '../src/chain/coin';
import { contractCode } from '../src/chain/contracts';
import { DEPLOY_BUDGET, contractId, encodeDeploy } from '../src/chain/tx';

const PRODUCTION = unhex('15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107');
const UNIT = 100_000_000n;
const DALER: coin.CoinOptions = {
  name: 'Daler',
  symbol: 'DLR',
  decimals: 8,
  maxSupply: 210_000_000n * UNIT,
  initialReward: 100n * UNIT,
  halvingInterval: 1_050_000n,
  tailReward: 0n,
  minter: coin.ZERO_ACCOUNT,
  allocations: [],
};

describe('coin deployment', () => {
  it('reproduces Daler on the production network', () => {
    const tx = encodeDeploy(contractCode('coin'), coin.encodeDeployInput(DALER), DEPLOY_BUDGET);
    expect(hex(contractId(PRODUCTION, tx))).toBe('305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4');
  });

  it('lays the input out as the wiki says', () => {
    const minter = new Uint8Array(32).fill(7);
    const to = new Uint8Array(32).fill(9);
    const bytes = coin.encodeDeployInput({ ...DALER, name: 'A', symbol: 'B', decimals: 2, minter, allocations: [{ account: to, amount: 5n }] });
    const expected =
      '01' + // version
      '01000000' + '41' + // Bytes("A")
      '01000000' + '42' + // Bytes("B")
      '02' + // decimals
      hex(le(210_000_000n * UNIT, 16)) +
      hex(le(100n * UNIT, 16)) +
      hex(le(1_050_000n, 8)) +
      hex(le(0n, 16)) +
      hex(minter) +
      hex(le(1n, 8)) +
      hex(to) +
      hex(le(5n, 16));
    expect(hex(bytes)).toBe(expected);
  });

  it('finds what the coin would refuse', () => {
    expect(coin.deployProblem(DALER)).toBeNull();
    expect(coin.deployProblem({ ...DALER, name: '' })).toMatch(/name/);
    expect(coin.deployProblem({ ...DALER, symbol: '' })).toMatch(/symbol/);
    // A right-to-left override would make the symbol read as another coin's.
    expect(coin.deployProblem({ ...DALER, symbol: '‮RLD' })).toMatch(/invisible or direction/);
    expect(coin.visibleLabel('D​LR‮')).toBe('D�LR�');
    expect(coin.visibleLabel('Daler · Ø')).toBe('Daler · Ø');
    expect(coin.deployProblem({ ...DALER, tailReward: DALER.initialReward + 1n })).toMatch(/tail/);
    expect(coin.deployProblem({ ...DALER, minter: coin.STATE_KEY })).toMatch(/minter/);
    expect(coin.deployProblem({ ...DALER, allocations: [{ account: coin.CONFIG_KEY, amount: 1n }] })).toMatch(/reserved/);
    expect(coin.deployProblem({ ...DALER, maxSupply: 10n, allocations: [{ account: new Uint8Array(32).fill(3), amount: 11n }] })).toMatch(/cap/);
    // An account named twice receives both amounts, so both count.
    const twice = { account: new Uint8Array(32).fill(3), amount: 6n };
    expect(coin.deployProblem({ ...DALER, maxSupply: 11n, allocations: [twice, twice] })).toMatch(/cap/);
  });

  it('computes the block reward of each era', () => {
    const config: coin.CoinConfig = { ...DALER, deployHeight: 443n };
    expect(coin.blockReward(config, 0n, 443n)).toBe(0n);
    expect(coin.blockReward(config, 0n, 444n)).toBe(100n * UNIT);
    expect(coin.blockReward(config, 0n, 443n + 1_050_000n)).toBe(100n * UNIT);
    expect(coin.blockReward(config, 0n, 444n + 1_050_000n)).toBe(50n * UNIT);
    expect(coin.blockReward({ ...config, tailReward: 70n * UNIT }, 0n, 444n + 1_050_000n)).toBe(70n * UNIT);
    // Never past the cap.
    expect(coin.blockReward(config, config.maxSupply - 3n, 444n)).toBe(3n);
  });
});

function le(v: bigint, n: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = Number((v >> BigInt(8 * i)) & 0xffn);
  return out;
}
