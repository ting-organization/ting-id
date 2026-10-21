// Restoring from a recovery phrase: the identities whose current keys list
// the phrase's key are found from the registry's Created events.
import { describe, expect, it } from 'vitest';
import { concat, equal, hex } from '../src/chain/bytes';
import * as idp from '../src/chain/identity';
import type { BlockEvents, ChainApi, StorageRead } from '../src/chain/node';
import { createdIds, findIdentitiesWithKey } from '../src/state/identityScan';

const registry = new Uint8Array(32).fill(0x1d);
const other = new Uint8Array(32).fill(0x2a);
const policy = new Uint8Array(32).fill(0x3f);
const phraseKey: idp.IdKey = { policy, params: new Uint8Array(65).fill(4) };
const otherKey: idp.IdKey = { policy, params: new Uint8Array(65).fill(5) };
const ids = [new Uint8Array(32).fill(0xa1), new Uint8Array(32).fill(0xb2), new Uint8Array(32).fill(0xc3)];
const created = (id: Uint8Array) => concat(idp.CREATED_TOPIC, id);

const core = (nextKeyId: bigint, keyEpoch = 0n): idp.Core => ({
  opNonce: 0n,
  changeEpoch: 0n,
  recoveryEpoch: 0n,
  keyEpoch,
  nextKeyId,
  keyCount: nextKeyId,
  guardianCount: 0n,
  slotCount: 2n,
  threshold: 0n,
  changeDelay: 0n,
  recoveryDelay: 0n,
  nextChange: 0n,
});

/**
 * A chain of 250 blocks: the identities are created in blocks 7, 130 and 249,
 * and another program emits a look-alike event. The first lists another key,
 * the second the phrase's key as its second key, and the third listed it in
 * an old key epoch only, as after a recovery.
 */
function node() {
  const records = new Map<string, Uint8Array>([
    [hex(idp.coreKey(ids[0])), idp.encodeCore(core(1n))],
    [hex(idp.keyKey(ids[0], 0n, 0n)), idp.encodeKeyRecord(otherKey)],
    [hex(idp.coreKey(ids[1])), idp.encodeCore(core(2n))],
    [hex(idp.keyKey(ids[1], 0n, 0n)), idp.encodeKeyRecord(otherKey)],
    [hex(idp.keyKey(ids[1], 0n, 1n)), idp.encodeKeyRecord(phraseKey)],
    [hex(idp.coreKey(ids[2])), idp.encodeCore(core(1n, 1n))],
    [hex(idp.keyKey(ids[2], 0n, 0n)), idp.encodeKeyRecord(phraseKey)],
    [hex(idp.keyKey(ids[2], 1n, 0n)), idp.encodeKeyRecord(otherKey)],
  ]);
  const at = new Map<number, BlockEvents['events']>([
    [7, [{ contract: registry, payload: created(ids[0]), transaction: '01' }]],
    [60, [{ contract: other, payload: created(new Uint8Array(32).fill(0xee)), transaction: '02' }]],
    [130, [{ contract: registry, payload: created(ids[1]), transaction: '03' }]],
    [249, [{ contract: registry, payload: created(ids[2]), transaction: '04' }]],
  ]);
  const asked: string[] = [];
  const height = 250;
  const api = {
    url: 'test',
    // The node's filter: blocks with events of `contract` and `topic` from `from` on, one block per page here.
    contractEvents: async (contract: Uint8Array, topic: Uint8Array | null, from: number) => {
      asked.push(`${hex(contract).slice(0, 4)}:${topic ? hex(topic).slice(0, 4) : '-'}:${from}`);
      const matching = [...at]
        .filter(([h]) => h >= from)
        .map(([h, events]) => ({ h, events: events.filter((e) => equal(e.contract, contract) && (!topic || equal(e.payload.subarray(0, 32), topic))) }))
        .filter((b) => b.events.length > 0);
      const first = matching[0];
      if (!first) return { blocks: [], next: Math.max(height + 1, from), height };
      return { blocks: [{ block: `b${first.h}`, height: first.h, time: 0, events: first.events }], next: first.h + 1, height };
    },
    storageMany: async (reads: StorageRead[]) => reads.map((r) => records.get(hex(r.key)) ?? null),
  } as unknown as ChainApi;
  return { api, asked };
}

describe('restoring from a recovery phrase', () => {
  it('finds the identities that list the key now, from the registry’s Created events', async () => {
    const { api, asked } = node();
    const seen: number[] = [];
    const found = await findIdentitiesWithKey(api, registry, phraseKey, (height) => seen.push(height));
    expect(found.map(hex)).toEqual([hex(ids[1])]);
    // One page per block with a Created event, then the page that says there is no more.
    const t = hex(idp.CREATED_TOPIC).slice(0, 4);
    expect(asked).toEqual([`1d1d:${t}:1`, `1d1d:${t}:8`, `1d1d:${t}:131`, `1d1d:${t}:250`]);
    expect(seen.at(-1)).toBe(250);
  });

  it('take Created events of the registry only', () => {
    const blocks: BlockEvents[] = [
      { block: 'x', height: 1, time: 0, events: [{ contract: other, payload: created(ids[0]), transaction: '' }, { contract: registry, payload: created(ids[1]), transaction: '' }] },
    ];
    expect(createdIds(blocks, registry).map(hex)).toEqual([hex(ids[1])]);
  });
});
