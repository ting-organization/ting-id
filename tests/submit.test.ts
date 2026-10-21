// A transaction posted to a node that gives no answer may still be in it: it
// is kept with its id, to be followed and sent again unchanged, and never
// counts as refused.
import { afterEach, describe, expect, it } from 'vitest';
import { hex } from '../src/chain/bytes';
import { MaybeSent, NodeClient, NodeError, setNodeFetch, setSubmitListener } from '../src/chain/node';
import { transactionId } from '../src/chain/tx';

const chainId = new Uint8Array(32).fill(0x26);
const tx = Uint8Array.of(1, 2, 3, 4);
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const status = json({ chain_id: hex(chainId), height: 10, limits: [1, 1, 1, 1], tip: '00', work: '1' });

afterEach(() => {
  setNodeFetch((input, init) => fetch(input, init));
  setSubmitListener(null);
});

describe('posting a transaction', () => {
  it('without an answer, keeps it under its own id and says it may be in', async () => {
    setNodeFetch(async (input, init) => {
      if (init?.method === 'POST') throw new TypeError('network request failed');
      return status.clone();
    });
    const kept: string[] = [];
    setSubmitListener((id, bytes) => kept.push(`${id}:${hex(bytes)}`));
    const id = hex(transactionId(chainId, tx));
    const error = await new NodeClient('http://node').submit(tx).catch((e) => e);
    expect(error).toBeInstanceOf(MaybeSent);
    expect(error.txId).toBe(id);
    expect(kept).toEqual([`${id}:${hex(tx)}`]);
  });

  it('refused by the node, keeps nothing and says why', async () => {
    setNodeFetch(async (input, init) => (init?.method === 'POST' ? json({ error: 'invalid signature' }, 400) : status.clone()));
    const kept: string[] = [];
    setSubmitListener((id) => kept.push(id));
    const error = await new NodeClient('http://node').submit(tx).catch((e) => e);
    expect(error).toBeInstanceOf(NodeError);
    expect(error).not.toBeInstanceOf(MaybeSent);
    expect(error.message).toBe('invalid signature');
    expect(kept).toEqual([]);
  });

  it('accepted, keeps it as submitted', async () => {
    const id = hex(transactionId(chainId, tx));
    setNodeFetch(async (input, init) => (init?.method === 'POST' ? json({ id }) : status.clone()));
    const kept: string[] = [];
    setSubmitListener((txId) => kept.push(txId));
    expect(await new NodeClient('http://node').submit(tx)).toBe(id);
    expect(kept).toEqual([id]);
  });
});
