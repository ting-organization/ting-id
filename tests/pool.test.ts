// Several nodes used as one: reads move on when a node fails, every answer is
// checked against the block it names, and the other nodes decide whether that
// block is the chain's.
import { describe, expect, it, vi } from 'vitest';
import { unhex } from '../src/chain/bytes';
import { AnswerTooLarge, NodeClient, NodeError, NodeOutdated, NodeStatus, ProvedReads, StorageProof } from '../src/chain/node';
import { CONFIRMATIONS, NodePool, nodeAddress } from '../src/chain/pool';
import fixture from './proof-fixture.json';

const chainId = unhex(fixture.chain_id);
const reads = fixture.values.map((v) => ({ contract: unhex(v.contract), key: unhex(v.key) }));
const values = fixture.values.map((v) => (typeof v.value_hex === 'string' ? unhex(v.value_hex) : null));
const answer = (): ProvedReads => ({
  values: values.map((v) => v && v.slice()),
  proofs: fixture.values.map((v) => ({
    end: v.proof.end as StorageProof['end'],
    otherKey: 'other_key' in v.proof ? unhex((v.proof as { other_key: string }).other_key) : null,
    siblings: v.proof.siblings.map((s: string) => unhex(s)),
  })),
  others: new Map(Object.entries(fixture.others as Record<string, string>).map(([k, v]) => [k, unhex(v)])),
  stateRoot: unhex(fixture.state_root),
  tip: fixture.tip,
});
/** The fixture's block height. */
const H = fixture.height;
const OTHER_BLOCK = 'ab'.repeat(32);

interface Fake {
  status?: Partial<NodeStatus>;
  storageProved?: () => Promise<ProvedReads>;
  /** Like storageProved, given the reads. */
  storageProvedReads?: (reads: unknown[]) => Promise<ProvedReads>;
  header?: () => Promise<Uint8Array | null>;
  /** The id of this node's block at the fixture's height. */
  blockAtH?: string;
  submit?: () => Promise<string>;
}

function pool(fakes: Record<string, Fake>) {
  const served: string[] = [];
  const make = (url: string) => {
    const f = fakes[url];
    return {
      url,
      status: async () => ({ chainId, height: H, tip: fixture.tip, work: 20n, limits: [], mempoolCount: 0, name: url, ...f.status }),
      storageProved: async (r: unknown[]) => {
        served.push(url);
        if (f.storageProvedReads) return f.storageProvedReads(r);
        return f.storageProved ? f.storageProved() : answer();
      },
      header: async () => (f.header ? f.header() : unhex(fixture.header)),
      blocks: async (height: number) => (height === H ? [{ id: f.blockAtH ?? fixture.tip, height, receipts: [] }] : []),
      submit: f.submit ?? (async () => 'tx'),
    } as unknown as NodeClient;
  };
  return { pool: new NodePool(Object.keys(fakes), make), served };
}

const unreachable = () => Promise.reject(new NodeError('cannot reach the node'));
const states = (p: NodePool) => Object.fromEntries(p.nodeHealth().map((h) => [h.url, h.state]));

describe('failover', () => {
  it('reads from the node with the most work, and from the next when it does not answer', async () => {
    let bDown = false;
    const { pool: p, served } = pool({ a: {}, b: { status: { work: 25n }, storageProved: () => (bDown ? unreachable() : Promise.resolve(answer())) } });
    expect((await p.status()).name).toBe('b');
    expect(await p.storageMany(reads)).toEqual(values);
    bDown = true;
    expect(await p.storageMany(reads)).toEqual(values);
    expect(served).toEqual(['b', 'b', 'a']);
    expect(states(p)).toEqual({ a: 'ok', b: 'down' });
  });

  it('leaves out a node on another chain, and says when no node answers', async () => {
    const { pool: p } = pool({ a: {}, b: {}, c: { status: { chainId: unhex('cd'.repeat(32)), work: 99n } } });
    expect((await p.status()).name).toBe('a');
    expect(states(p).c).toBe('other-chain');
    const { pool: none } = pool({ a: { status: undefined } });
    (none as unknown as { nodes: { status: () => Promise<never> }[] }).nodes[0].status = unreachable;
    await expect(none.status()).rejects.toThrow('cannot reach the node');
  });

  it('marks a node without proofs as needing an update', async () => {
    const { pool: p, served } = pool({ a: { storageProved: () => Promise.reject(new NodeOutdated('the node gives no proofs')) }, b: {} });
    await p.status();
    expect(await p.storageMany(reads)).toEqual(values);
    expect(served).toEqual(['a', 'b']);
    expect(states(p).a).toBe('outdated');
    // It stays so until the user refreshes, though it answers.
    await p.status();
    expect(states(p).a).toBe('outdated');
    p.reset();
    await p.status();
    expect(states(p).a).toBe('ok');
  });
});

describe('addresses', () => {
  it('take plain http only for this device and the home network', () => {
    for (const ok of ['https://node1.ting-node.com', 'https://2.29.97.212:18441/', 'http://127.0.0.1:18441', 'http://localhost:18441', 'http://192.168.1.20:18441', 'http://10.0.0.5', 'http://172.20.1.1:80']) {
      expect(nodeAddress(ok)).toBe(ok.replace(/\/$/, ''));
    }
    for (const refused of ['http://2.29.97.212:18441', 'http://node1.ting-node.com', 'http://172.32.0.1', 'http://192.168.1.20@example.com', 'http://300.168.1.1', 'http://[::1]:18441', 'ftp://127.0.0.1', 'https://']) {
      expect(nodeAddress(refused)).toBeNull();
    }
  });

  it('leave out a saved node reached over plain http from elsewhere', async () => {
    const { pool: p, served } = pool({ 'http://2.29.97.212:18441': {}, b: {} });
    await p.status();
    expect(states(p)['http://2.29.97.212:18441']).toBe('insecure');
    expect(await p.storageMany(reads)).toEqual(values);
    expect(served).toEqual(['b']);
    const { pool: alone } = pool({ 'http://2.29.97.212:18441': {} });
    await expect(alone.status()).rejects.toThrow('plain http');
  });
});

describe('records that never change', () => {
  it('are read from every node once and kept', async () => {
    const { pool: p, served } = pool({ a: {}, b: {} });
    await p.status();
    expect(await p.storageFixed(reads)).toEqual(values);
    expect(served.sort()).toEqual(['a', 'b']);
    expect((p as unknown as { fixed: Map<string, Uint8Array> }).fixed.size).toBe(values.filter((v) => v !== null).length);
  });

  it('leave out a node whose answer fails its check', async () => {
    const lying = () => {
      const a = answer();
      a.values[0]![0] ^= 1;
      return Promise.resolve(a);
    };
    const { pool: p } = pool({ a: { storageProved: lying }, b: {} });
    await p.status();
    expect(await p.storageFixed(reads)).toEqual(values);
    expect(states(p).a).toBe('distrusted');
  });
});

describe('large answers', () => {
  it('are asked for again in halves when the node refuses one as too large', async () => {
    const asked: number[] = [];
    const halves = (reads: unknown[]) => {
      asked.push(reads.length);
      if (reads.length > 2) return Promise.reject(new AnswerTooLarge('JSON response size limit', 400));
      const full = answer();
      const from = asked.length === 2 ? 0 : 2;
      return Promise.resolve({ ...full, values: full.values.slice(from, from + 2), proofs: full.proofs.slice(from, from + 2) });
    };
    const { pool: p } = pool({ a: { storageProvedReads: halves } });
    await p.status();
    expect(await p.storageMany(reads)).toEqual(values);
    expect(asked).toEqual([4, 2, 2]);
  });
});

describe('checks', () => {
  it('stop using a node whose values do not match the state it names', async () => {
    const lying = () => {
      const a = answer();
      a.values[0]![0] ^= 1;
      return Promise.resolve(a);
    };
    const { pool: p, served } = pool({ a: { status: { work: 30n }, storageProved: lying }, b: {} });
    const distrusted = vi.fn();
    p.onDistrust = distrusted;
    await p.status();
    expect(await p.storageMany(reads)).toEqual(values);
    expect(served).toEqual(['a', 'b']);
    expect(states(p).a).toBe('distrusted');
    expect(p.notice).toEqual({ kind: 'distrusted' });
    expect(distrusted).toHaveBeenCalledOnce();
  });

  it('stop using a node whose header is not the block’s', async () => {
    const bytes = unhex(fixture.header);
    bytes[180] ^= 1;
    const { pool: p, served } = pool({ a: { status: { work: 30n }, header: async () => bytes }, b: {} });
    await p.status();
    expect(await p.storageMany(reads)).toEqual(values);
    expect(served).toEqual(['a', 'b']);
    expect(p.nodeHealth()[0]).toMatchObject({ state: 'distrusted', problem: 'it gave a block header that is not the block’s' });
  });

  it('let the other nodes confirm the block the answers come from', async () => {
    const { pool: p } = pool({ a: { status: { work: 30n } }, b: {} });
    await p.status();
    await p.storageMany(reads);
    await vi.waitFor(() => expect((p as unknown as { confirmed: Set<string> }).confirmed.has(fixture.tip)).toBe(true));
    expect(p.notice).toBeNull();
  });

  it('outvote a node that follows another chain', async () => {
    const past = { height: H + CONFIRMATIONS };
    const { pool: p } = pool({ a: { status: { work: 30n } }, b: { status: past, blockAtH: OTHER_BLOCK }, c: { status: past, blockAtH: OTHER_BLOCK } });
    await p.status();
    await p.storageMany(reads);
    await vi.waitFor(() => expect(states(p).a).toBe('distrusted'));
    expect(p.notice).toEqual({ kind: 'distrusted' });
  });

  it('say two nodes split, when neither has a majority', async () => {
    const { pool: p } = pool({ a: { status: { work: 30n } }, b: { status: { height: H + CONFIRMATIONS }, blockAtH: OTHER_BLOCK } });
    await p.status();
    await p.storageMany(reads);
    await vi.waitFor(() => expect(p.notice).toEqual({ kind: 'split' }));
    expect(states(p)).toEqual({ a: 'ok', b: 'ok' });
  });

  it('wait while the other node has not moved past the block', async () => {
    const { pool: p } = pool({ a: { status: { work: 30n } }, b: { status: { height: H + CONFIRMATIONS - 1 }, blockAtH: OTHER_BLOCK } });
    await p.status();
    await p.storageMany(reads);
    await new Promise((r) => setTimeout(r, 10));
    expect(p.notice).toBeNull();
  });
});

describe('transactions', () => {
  it('go to every node; the best one that answers decides', async () => {
    const sent: string[] = [];
    const accept = (url: string) => async () => {
      sent.push(url);
      return 'tx';
    };
    const { pool: p } = pool({ a: { submit: accept('a') }, b: { status: { work: 30n }, submit: () => (sent.push('b'), unreachable()) } });
    await p.status();
    expect(await p.submit(Uint8Array.of(1))).toBe('tx');
    expect(sent.sort()).toEqual(['a', 'b']);
    expect(states(p).b).toBe('down');
  });

  it('are refused when the best node refuses them, whatever the others say', async () => {
    const { pool: p } = pool({ a: {}, b: { status: { work: 30n }, submit: () => Promise.reject(new NodeError('nonce too low', 400)) } });
    await p.status();
    await expect(p.submit(Uint8Array.of(1))).rejects.toThrow('nonce too low');
  });
});
