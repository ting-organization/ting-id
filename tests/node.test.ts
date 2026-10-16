// Reading many storage records at once (NodeClient.storageMany): one request
// per 1,024 records, values in the order asked, and single reads for a node
// without the batch call.
import { afterEach, describe, expect, it } from 'vitest';
import { hex } from '../src/chain/bytes';
import { NodeClient, setNodeFetch } from '../src/chain/node';

const contract = new Uint8Array(32).fill(1);
const key = (n: number) => {
  const k = new Uint8Array(32);
  new DataView(k.buffer).setUint32(28, n);
  return k;
};
/** A record's value in the fake node: its key's last two bytes, absent for odd numbers. */
const valueOf = (keyHex: string): string | null => (parseInt(keyHex.slice(-2), 16) % 2 ? null : keyHex.slice(-4));
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

function fakeNode(batch: boolean) {
  const calls: string[] = [];
  setNodeFetch(async (input, init) => {
    const url = String(input);
    calls.push(`${init?.method ?? 'GET'} ${url.replace(/^.*\/v1/, '/v1')}`);
    if (url.endsWith('/v1/storage')) {
      if (!batch) return json({ error: 'not found' }, 404);
      const { reads } = JSON.parse(String(init!.body));
      return json({ state_root: '00', tip: '00', values: reads.map((r: { contract: string; key: string }) => ({ ...r, value_hex: valueOf(r.key) })) });
    }
    const keyHex = url.split('/storage/')[1];
    return json({ value_hex: valueOf(keyHex) });
  });
  return calls;
}

afterEach(() => setNodeFetch((input, init) => fetch(input, init)));

describe('reading many records', () => {
  it('answers in the order asked, null where absent, 1,024 at a time', async () => {
    const calls = fakeNode(true);
    const reads = Array.from({ length: 1500 }, (_, i) => ({ contract, key: key(i) }));
    const values = await new NodeClient('http://node').storageMany(reads);
    expect(values).toHaveLength(1500);
    expect(values[0] && hex(values[0])).toBe('0000');
    expect(values[1]).toBeNull();
    expect(values[1498] && hex(values[1498])).toBe(hex(key(1498)).slice(-4));
    expect(calls).toEqual(['POST /v1/storage', 'POST /v1/storage']);
  });

  it('reads one record at a time from a node without the batch call, and remembers it', async () => {
    const calls = fakeNode(false);
    const node = new NodeClient('http://node');
    const values = await node.storageMany([0, 1, 2].map((i) => ({ contract, key: key(i) })));
    expect(values.map((v) => v && hex(v))).toEqual(['0000', null, '0002']);
    expect(calls.filter((c) => c.startsWith('POST'))).toHaveLength(1);
    await node.storageMany([{ contract, key: key(4) }]);
    expect(calls.filter((c) => c.startsWith('POST'))).toHaveLength(1);
  });
});
