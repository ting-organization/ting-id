// Finding the identities that list a key, for a restore from a recovery
// phrase (RECOVERY.md). An identity's ID hashes its whole creation, guardians
// included, so the words alone cannot give it: the app asks a node for the
// registry's Created events, one per identity, then reads each identity's
// current keys. Events come from any node; the keys are read with proofs, so
// a node can hide an identity but never invent one.
import { equal, hex } from '@/chain/bytes';
import * as idp from '@/chain/identity';
import { BlockEvents, ChainApi, NodeError, StorageRead } from '@/chain/node';

/** Blocks with matching events per request, the node's most. */
const EVENTS_PAGE = 100;

/** The IDs the registry created in these blocks. */
export function createdIds(blocks: BlockEvents[], registry: Uint8Array): Uint8Array[] {
  const out: Uint8Array[] = [];
  for (const b of blocks) {
    for (const e of b.events) {
      if (!equal(e.contract, registry)) continue;
      const event = idp.decodeIdEvent(e.payload);
      if (event?.kind === 'created') out.push(event.id);
    }
  }
  return out;
}

/** Which of `ids` list `key` in their current key epoch: two batch reads in all. */
export async function identitiesWithKey(node: ChainApi, registry: Uint8Array, key: idp.IdKey, ids: Uint8Array[]): Promise<Uint8Array[]> {
  if (ids.length === 0) return [];
  const cores = await node.storageMany(ids.map((id) => ({ contract: registry, key: idp.coreKey(id) })));
  const reads: StorageRead[] = [];
  const owners: number[] = [];
  ids.forEach((id, i) => {
    const core = cores[i] && idp.decodeCore(cores[i]!);
    if (!core) return;
    for (let k = 0n; k < core.nextKeyId; k++) {
      reads.push({ contract: registry, key: idp.keyKey(id, core.keyEpoch, k) });
      owners.push(i);
    }
  });
  const records = await node.storageMany(reads);
  const hits = new Set<number>();
  records.forEach((bytes, j) => {
    const record = bytes && idp.decodeKeyRecord(bytes);
    if (record && equal(record.policy, key.policy) && equal(record.params, key.params)) hits.add(owners[j]);
  });
  return ids.filter((_, i) => hits.has(i));
}

/** The identities on the chain that list `key`; `progress` hears the height read up to and the chain's height. */
export async function findIdentitiesWithKey(
  node: ChainApi,
  registry: Uint8Array,
  key: idp.IdKey,
  progress?: (height: number, tip: number) => void,
): Promise<Uint8Array[]> {
  const ids = new Map<string, Uint8Array>();
  for (let from = 1; ; ) {
    const page = await node.contractEvents(registry, idp.CREATED_TOPIC, from, EVENTS_PAGE);
    for (const id of createdIds(page.blocks, registry)) ids.set(hex(id), id);
    progress?.(Math.min(page.next - 1, page.height), page.height);
    if (page.next > page.height) break;
    if (page.next <= from) throw new NodeError('the node gave an events page that does not move on');
    from = page.next;
  }
  return identitiesWithKey(node, registry, key, [...ids.values()]);
}
