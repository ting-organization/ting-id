// Several nodes of one chain, used as one (Settings → Nodes). Reads go to the
// node furthest ahead and move on to the next when one does not answer;
// transactions go to every node. Answers are checked: each storage value by
// its proof against the state root of the block the node names, that root by
// the block's header, which must hash to the block's id, and the block itself
// by the other nodes, which must have it on their chain too. Records that never
// change, such as a coin's configuration, are read from every node, which must
// agree. A node that fails a check is not used again until the user refreshes.
import { equal, hex } from './bytes';
import {
  AnswerTooLarge,
  BlockEvents,
  BlockReceipts,
  ChainApi,
  EventPage,
  NodeClient,
  NodeError,
  NodeOutdated,
  NodeStatus,
  STORAGE_BATCH,
  StorageRead,
  TransactionLookup,
  nodeFailed,
} from './node';
import { checkHeader, recordKey, verifyRecord } from './proof';

/** Blocks another node must be past a block's height, on another branch, before the nodes count as following different chains. */
export const CONFIRMATIONS = 3;
/** Checked blocks remembered. */
const REMEMBER = 64;

export type NodeState =
  /** Not asked yet. */
  | 'unknown'
  /** Answers, on the chain the pool follows. */
  | 'ok'
  /** Does not answer. */
  | 'down'
  /** Lacks a call the app needs. */
  | 'outdated'
  /** Answers from another chain than the other nodes. */
  | 'other-chain'
  /** Gave an answer that failed a check. */
  | 'distrusted'
  /** Plain http to a node elsewhere: anyone on the way could change its answers. */
  | 'insecure';

export interface NodeHealth {
  url: string;
  state: NodeState;
  /** Its last status. */
  status: NodeStatus | null;
  /** What went wrong, in words, when it is not used. */
  problem: string | null;
  /** The node reads go to first. */
  best: boolean;
}

/** A node address as typed, cleaned up; null when it is not one, or is plain http to a node elsewhere. */
export function nodeAddress(text: string): string | null {
  const url = text.trim().replace(/\/+$/, '');
  const m = /^(https?):\/\/([^\s/?#@]+)(\/[^\s?#]*)?$/i.exec(url);
  if (!m) return null;
  return m[1].toLowerCase() === 'https' || nearby(m[2]) ? url : null;
}

/** Whether an address is plain http to a node elsewhere, which is refused. */
export function remoteHttp(text: string): boolean {
  const m = /^http:\/\/([^\s/?#@]+)/i.exec(text.trim());
  return !!m && !nearby(m[1]);
}

/** A host on this device or the home network (a private IPv4 address), where plain http is allowed. */
function nearby(hostPort: string): boolean {
  const host = hostPort.replace(/:\d+$/, '').toLowerCase();
  if (host === 'localhost') return true;
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host)?.slice(1).map(Number);
  if (!v4 || v4.some((n) => n > 255)) return false;
  const [a, b] = v4;
  return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/** Something about the nodes the user should know, beyond one not answering. */
export type PoolNotice = { kind: 'distrusted' } | { kind: 'split' } | null;

/** A node failed a check: the pool stops using it. */
class NodeDistrusted extends NodeError {}

export class NodePool implements ChainApi {
  readonly url: string;
  /** Changes when a node's health or the notice changes. */
  revision = 0;
  notice: PoolNotice = null;
  /** Told when a node is found to have given wrong answers, so what came from it is read again. */
  onDistrust: (() => void) | null = null;
  private readonly nodes: NodeClient[];
  private health = new Map<string, NodeHealth>();
  private chain: string | null = null;
  /** Headers checked: block id → its state root (hex) and height. */
  private headers = new Map<string, { root: string; height: number }>();
  /** Header reads on their way, by node and block, so reads at one block fetch it once. */
  private headerReads = new Map<string, Promise<Uint8Array | null>>();
  /** Blocks answers came from that no other node has confirmed yet, and which node gave them. */
  private pending = new Map<string, { height: number; servedBy: string }>();
  private confirmed = new Set<string>();
  /** Records that never change, as the nodes agreed on them, by record key. */
  private fixed = new Map<string, Uint8Array>();
  private checking = false;
  private listeners = new Set<() => void>();

  constructor(
    readonly urls: string[],
    make: (url: string) => NodeClient = (url) => new NodeClient(url),
  ) {
    this.url = urls.join(' ');
    this.nodes = urls.map(make);
    for (const url of urls) {
      const insecure = remoteHttp(url);
      this.health.set(url, { url, state: insecure ? 'insecure' : 'unknown', status: null, problem: insecure ? 'plain http is only for a node on this device or your home network; use its https address' : null, best: false });
    }
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getRevision = (): number => this.revision;

  getNotice = (): PoolNotice => this.notice;

  /** Every node's health, in the user's order. */
  nodeHealth(): NodeHealth[] {
    return this.urls.map((u) => this.health.get(u)!);
  }

  /** Gives distrusted and outdated nodes another chance and forgets the checks, as the user asked to refresh. */
  reset() {
    for (const h of this.health.values()) if (h.state === 'distrusted' || h.state === 'outdated') Object.assign(h, { state: 'unknown', problem: null });
    this.headers.clear();
    this.pending.clear();
    this.confirmed.clear();
    this.fixed.clear();
    this.notice = null;
    this.changed();
  }

  private changed() {
    this.revision++;
    for (const l of this.listeners) l();
  }

  private set(node: NodeClient, patch: Partial<NodeHealth>, quiet = false) {
    Object.assign(this.health.get(node.url)!, patch);
    if (!quiet) this.changed();
  }

  /** Whether a finding sticks until the user refreshes. */
  private stuck(node: NodeClient): boolean {
    const state = this.health.get(node.url)!.state;
    return state === 'distrusted' || state === 'outdated';
  }

  /** Notes why a node failed; another may answer instead. */
  private fail(node: NodeClient, e: unknown) {
    if (e instanceof NodeDistrusted || this.stuck(node)) return;
    if (e instanceof NodeOutdated) this.set(node, { state: 'outdated', problem: e.message, best: false });
    else this.set(node, { state: 'down', problem: (e as Error).message, best: false });
  }

  private distrust(node: NodeClient, problem: string): NodeDistrusted {
    this.set(node, { state: 'distrusted', problem, best: false });
    this.fixed.clear();
    this.notice = { kind: 'distrusted' };
    this.changed();
    this.onDistrust?.();
    return new NodeDistrusted(problem);
  }

  /**
   * Every node's status. The pool follows the chain most nodes are on (on a
   * tie, the one of the node listed first) and reports the status of its node
   * with the most work.
   */
  async status(): Promise<NodeStatus> {
    const answers = await Promise.all(
      this.nodes
        .filter((node) => this.health.get(node.url)!.state !== 'insecure')
        .map(async (node) => {
          try {
            return { node, status: await node.status() };
          } catch (e) {
            this.fail(node, e);
            return null;
          }
        }),
    );
    const up = answers.filter((a): a is { node: NodeClient; status: NodeStatus } => a !== null);
    if (up.length === 0) throw new NodeError(this.nodes.length === 1 ? (this.health.get(this.urls[0])!.problem ?? 'the node does not answer') : 'no node answers');
    const votes = new Map<string, number>();
    for (const a of up) votes.set(hex(a.status.chainId), (votes.get(hex(a.status.chainId)) ?? 0) + 1);
    const most = Math.max(...votes.values());
    const chain = hex(up.find((a) => votes.get(hex(a.status.chainId)) === most)!.status.chainId);
    if (chain !== this.chain) this.fixed.clear();
    this.chain = chain;
    for (const a of up) {
      if (hex(a.status.chainId) !== this.chain) this.set(a.node, { state: 'other-chain', status: a.status, problem: 'it is on another chain than your other nodes', best: false }, true);
      else if (this.stuck(a.node)) this.set(a.node, { status: a.status }, true);
      else this.set(a.node, { state: 'ok', status: a.status, problem: null }, true);
    }
    const usable = this.usable();
    if (usable.length === 0) this.changed();
    if (usable.length === 0) throw new NodeError(up.map((a) => this.health.get(a.node.url)!.problem).find(Boolean) ?? 'no node can be used');
    for (const node of this.nodes) this.health.get(node.url)!.best = node === usable[0];
    this.changed();
    void this.recheck();
    return this.health.get(usable[0].url)!.status!;
  }

  /** The nodes answering on the chain, the most work first, then in the user's order. */
  private usable(): NodeClient[] {
    const ok = this.nodes.filter((n) => this.health.get(n.url)!.state === 'ok');
    const work = (n: NodeClient) => this.health.get(n.url)!.status?.work ?? 0n;
    return ok.sort((a, b) => (work(a) === work(b) ? this.nodes.indexOf(a) - this.nodes.indexOf(b) : work(a) > work(b) ? -1 : 1));
  }

  /** The nodes to ask, in order: the usable ones, then ones not known to fail. */
  private order(): NodeClient[] {
    const usable = this.usable();
    const maybe = this.nodes.filter((n) => !usable.includes(n) && ['unknown', 'down'].includes(this.health.get(n.url)!.state));
    return [...usable, ...maybe];
  }

  /** `ask` of the first node that answers; a refusal of the request itself is the answer. */
  private async first<T>(ask: (node: NodeClient) => Promise<T>): Promise<T> {
    let failure: unknown = new NodeError('no node answers');
    for (const node of this.order()) {
      try {
        return await ask(node);
      } catch (e) {
        if (!nodeFailed(e)) throw e;
        this.fail(node, e);
        failure = e;
      }
    }
    throw failure;
  }

  async storage(contract: Uint8Array, key: Uint8Array): Promise<Uint8Array | null> {
    return (await this.storageMany([{ contract, key }]))[0];
  }

  /** Records, each checked by its proof against the state of the block the node names. */
  async storageMany(reads: StorageRead[]): Promise<(Uint8Array | null)[]> {
    const out: (Uint8Array | null)[] = [];
    for (let i = 0; i < reads.length; i += STORAGE_BATCH) out.push(...(await this.provedBatch(reads.slice(i, i + STORAGE_BATCH))));
    return out;
  }

  private provedBatch(reads: StorageRead[]): Promise<(Uint8Array | null)[]> {
    return this.first((node) => this.provedFrom(node, reads));
  }

  /**
   * Records that never change once written, from every node, each checked as
   * any read: all nodes that have a record must give the same, so one node
   * alone cannot change, say, a coin's decimals. A record no node has yet is
   * read again next time.
   */
  async storageFixed(reads: StorageRead[]): Promise<(Uint8Array | null)[]> {
    const keys = reads.map((r) => hex(recordKey(r.contract, r.key)));
    const todo = reads.filter((_, i) => !this.fixed.has(keys[i]));
    if (todo.length > 0) {
      const nodes = this.usable();
      if (nodes.length === 0) return this.storageMany(reads);
      const answers = await Promise.all(
        nodes.map(async (node) => {
          try {
            const out: (Uint8Array | null)[] = [];
            for (let i = 0; i < todo.length; i += STORAGE_BATCH) out.push(...(await this.provedFrom(node, todo.slice(i, i + STORAGE_BATCH))));
            return out;
          } catch (e) {
            if (!nodeFailed(e)) throw e;
            this.fail(node, e);
            return null;
          }
        }),
      );
      const got = answers.filter((a): a is (Uint8Array | null)[] => a !== null);
      if (got.length === 0) throw new NodeError('no node answers');
      const todoKeys = todo.map((r) => hex(recordKey(r.contract, r.key)));
      todoKeys.forEach((key, i) => {
        const present = got.map((a) => a[i]).filter((v): v is Uint8Array => v !== null);
        if (present.some((v) => !equal(v, present[0]))) {
          if (this.notice?.kind !== 'distrusted') this.notice = { kind: 'split' };
          this.changed();
          throw new NodeError('your nodes give different values for a record that never changes');
        }
        if (present.length > 0) this.fixed.set(key, present[0]);
      });
    }
    return keys.map((k) => this.fixed.get(k) ?? null);
  }

  /** `reads` from one node, checked; an answer too large for one response is asked for in halves. */
  private async provedFrom(node: NodeClient, reads: StorageRead[]): Promise<(Uint8Array | null)[]> {
    let answer;
    try {
      answer = await node.storageProved(reads);
    } catch (e) {
      if (!(e instanceof AnswerTooLarge) || reads.length < 2) throw e;
      const half = Math.ceil(reads.length / 2);
      return [...(await this.provedFrom(node, reads.slice(0, half))), ...(await this.provedFrom(node, reads.slice(half)))];
    }
    reads.forEach((r, i) => {
      if (!verifyRecord(answer.stateRoot, recordKey(r.contract, r.key), answer.values[i], answer.proofs[i], answer.others)) {
        throw this.distrust(node, 'a value it gave does not match the state it read it from');
      }
    });
    await this.checkBlock(node, answer.tip, hex(answer.stateRoot));
    return answer.values;
  }

  /** That `root` is the state root of block `tip`, by its header; then has the other nodes confirm the block. */
  private async checkBlock(node: NodeClient, tip: string, root: string) {
    let known = this.headers.get(tip);
    if (!known) {
      const asked = `${node.url} ${tip}`;
      let reading = this.headerReads.get(asked);
      if (!reading) {
        reading = node.header(tip);
        this.headerReads.set(asked, reading);
        reading.then(
          () => this.headerReads.delete(asked),
          () => this.headerReads.delete(asked),
        );
      }
      const bytes = await reading;
      if (!bytes) throw new NodeError('the node does not give the block it read from');
      const header = checkHeader(tip, bytes);
      if (!header) throw this.distrust(node, 'it gave a block header that is not the block’s');
      if (hex(header.chainId) !== this.chain) throw this.distrust(node, 'it read from a block of another chain');
      known = { root: hex(header.stateRoot), height: header.height };
      this.headers.set(tip, known);
      if (this.headers.size > REMEMBER) this.headers.delete(this.headers.keys().next().value!);
    }
    if (known.root !== root) throw this.distrust(node, 'its values are not from the block it named');
    if (!this.confirmed.has(tip) && !this.pending.has(tip)) {
      this.pending.set(tip, { height: known.height, servedBy: node.url });
      if (this.pending.size > REMEMBER) this.pending.delete(this.pending.keys().next().value!);
      void this.recheck();
    }
  }

  /**
   * Asks the other nodes whether the blocks answers came from are on their
   * chain. One that has another block there, and has moved on by
   * CONFIRMATIONS blocks, follows another chain: the majority of the nodes
   * decides which is wrong, and without one the pool says the nodes split.
   */
  private async recheck() {
    if (this.checking) return;
    this.checking = true;
    try {
      for (const [tip, p] of [...this.pending]) {
        const others = this.nodes.filter((n) => n.url !== p.servedBy && this.health.get(n.url)!.state === 'ok');
        for (const other of others) {
          const height = this.health.get(other.url)!.status?.height ?? 0;
          if (height < p.height) continue;
          const [block] = await other.blocks(p.height, 1).catch(() => [] as BlockReceipts[]);
          if (!block) continue;
          if (block.id === tip) {
            this.confirmed.add(tip);
            if (this.confirmed.size > REMEMBER) this.confirmed.delete(this.confirmed.values().next().value!);
            this.pending.delete(tip);
            // The nodes agree again.
            if (this.notice?.kind === 'split') {
              this.notice = null;
              this.changed();
            }
            break;
          }
          if (height >= p.height + CONFIRMATIONS) {
            this.pending.delete(tip);
            await this.settle(p.height);
            break;
          }
        }
      }
    } finally {
      this.checking = false;
    }
  }

  /** The nodes have different blocks at `height`: those outvoted are distrusted, or with no majority the pool says they split. */
  private async settle(height: number) {
    const ids = new Map<NodeClient, string>();
    for (const node of this.nodes.filter((n) => this.health.get(n.url)!.state === 'ok')) {
      const [block] = await node.blocks(height, 1).catch(() => [] as BlockReceipts[]);
      if (block) ids.set(node, block.id);
    }
    const counts = new Map<string, number>();
    for (const id of ids.values()) counts.set(id, (counts.get(id) ?? 0) + 1);
    const ranked = [...counts].sort((a, b) => b[1] - a[1]);
    if (ranked.length < 2) return;
    if (ranked[0][1] > ranked[1][1]) {
      for (const [node, id] of ids) if (id !== ranked[0][0]) this.distrust(node, 'it follows another chain than your other nodes');
    } else if (this.notice?.kind !== 'distrusted') {
      this.notice = { kind: 'split' };
      this.changed();
    }
  }

  /** Sent to every node; the answer of the best one that answers decides, the others only pass it on. */
  async submit(tx: Uint8Array, notBefore?: number): Promise<string> {
    const targets = this.order();
    if (targets.length === 0) throw new NodeError('no node answers');
    const sent = targets.map((node) => node.submit(tx, notBefore));
    for (const s of sent) s.catch(() => {});
    let failure: unknown = null;
    for (let i = 0; i < targets.length; i++) {
      try {
        return await sent[i];
      } catch (e) {
        if (!nodeFailed(e)) throw e;
        this.fail(targets[i], e);
        failure ??= e;
      }
    }
    throw failure;
  }

  transaction(id: string): Promise<TransactionLookup | null> {
    return this.first((node) => node.transaction(id));
  }

  blocks(from: number, limit?: number): Promise<BlockReceipts[]> {
    return this.first((node) => node.blocks(from, limit));
  }

  events(from: number, limit?: number): Promise<BlockEvents[]> {
    return this.first((node) => node.events(from, limit));
  }

  contractEvents(contract: Uint8Array, topic: Uint8Array | null, from: number, limit?: number): Promise<EventPage> {
    return this.first((node) => node.contractEvents(contract, topic, from, limit));
  }

  contract(id: Uint8Array): Promise<{ codeHash: string; codeBytes: number } | null> {
    return this.first((node) => node.contract(id));
  }

  async contractExists(id: Uint8Array): Promise<boolean> {
    return (await this.contract(id)) !== null;
  }
}
