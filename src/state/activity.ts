// The user's activity on screen: one scan of the chain's events per user and
// chain (./scan), shared by every screen, read forward as blocks arrive and
// backward when the user asks for older activity, and saved on the device so
// the next start reads only the blocks it has not seen.
import { useEffect, useMemo, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { hex } from '@/chain/bytes';
import type { BlockEvents, ChainApi } from '@/chain/node';
import { ActivityItem, useNodeClient } from './chain';
import { REORG_DEPTH, SavedScan, ScanScope, activityOf, addNewer, addOlder, emptyScan, isSavedScan, rollBack, scopeKey, touches, trimmed } from './scan';

/** Blocks per events request, the node's page limit. */
const PAGE = 100;
/** Event requests sent at once when reading back. */
const PARALLEL = 4;
/** Blocks one search for older activity reads at most, about 3.5 days of 60-second blocks. */
const SEARCH_BLOCKS = 5_000;
/** Items a search for older activity looks for before it stops. */
const SEARCH_ITEMS = 20;
/** Items a list shows at first, and adds each time it is scrolled to the end. */
export const SHOWN = 20;
/** Items saved on the device; older ones are found again by searching back. */
const KEEP = 1_000;

const STORAGE_PREFIX = 'ting-id-activity:';
/** When each saved scan was last used (scope key → milliseconds), so the least used go first. */
const USED = 'ting-id-activity-used';
/** Saved scans kept at most: one per user and chain, and accounts a user no longer has, leave in time. */
const KEEP_SCANS = 12;

/** One update of the saved scans' use at a time: each reads what the one before wrote. */
let usedQueue: Promise<void> = Promise.resolve();

/** Notes that the scan of `key` is in use, and removes the saved scans least recently used past KEEP_SCANS. */
function noteUsed(key: string): Promise<void> {
  usedQueue = usedQueue
    .then(async () => {
      let used: Record<string, number> = {};
      try {
        const text = await AsyncStorage.getItem(USED);
        if (text) used = JSON.parse(text);
        else {
          // Scans saved before their use was noted count as used longest ago.
          for (const name of await AsyncStorage.getAllKeys()) if (name.startsWith(STORAGE_PREFIX)) used[name.slice(STORAGE_PREFIX.length)] = 0;
        }
      } catch {
        used = {};
      }
      used[key] = Date.now();
      const old = Object.entries(used)
        .sort((a, b) => b[1] - a[1])
        .slice(KEEP_SCANS)
        .map(([k]) => k);
      for (const k of old) delete used[k];
      if (old.length > 0) await AsyncStorage.multiRemove(old.map((k) => STORAGE_PREFIX + k));
      await AsyncStorage.setItem(USED, JSON.stringify(used));
    })
    .catch(() => {});
  return usedQueue;
}

/** Blocks `from` to `to`, all of them, in order: a page the node cuts short is continued. */
async function readRange(node: ChainApi, from: number, to: number): Promise<BlockEvents[]> {
  const spans: [number, number][] = [];
  for (let a = from; a <= to; a += PAGE) spans.push([a, Math.min(to, a + PAGE - 1)]);
  const out: BlockEvents[][] = [];
  for (let i = 0; i < spans.length; i += PARALLEL) {
    out.push(
      ...(await Promise.all(
        spans.slice(i, i + PARALLEL).map(async ([a, b]) => {
          const blocks: BlockEvents[] = [];
          for (let next = a; next <= b; ) {
            const page = (await node.events(next, b - next + 1)).filter((x) => x.height <= b);
            if (page.length === 0) break;
            blocks.push(...page);
            next = page[page.length - 1].height + 1;
          }
          return blocks;
        }),
      )),
    );
  }
  return out.flat();
}

/** One user's scan on one chain, shared by every screen that shows it. */
class Scan {
  saved: SavedScan | null = null;
  loaded = false;
  searching = false;
  activity: ActivityItem[] = [];
  version = 0;
  node: ChainApi;
  private queue: Promise<void> = Promise.resolve();
  private listeners = new Set<() => void>();
  /** The height the scan was last brought up to, so several screens at one height read once. */
  private synced = 0;
  /** Set once everything was deleted: what is still on the way is saved nowhere. */
  dropped = false;
  private noted = false;

  constructor(
    readonly key: string,
    readonly scope: ScanScope,
    node: ChainApi,
  ) {
    this.node = node;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Runs one step after the ones before it, never two at once. */
  private run(step: () => Promise<void>): Promise<void> {
    this.queue = this.queue.then(step).catch(() => {});
    return this.queue;
  }

  private changed(save: boolean) {
    if (this.saved) this.activity = activityOf(this.saved.items, this.scope.registry);
    this.version++;
    for (const l of this.listeners) l();
    if (!save || !this.saved || this.dropped) return;
    AsyncStorage.setItem(STORAGE_PREFIX + this.key, JSON.stringify(trimmed(this.saved, KEEP))).catch(() => {});
    if (!this.noted) {
      this.noted = true;
      noteUsed(this.key);
    }
  }

  private async load() {
    if (this.loaded) return;
    try {
      const text = await AsyncStorage.getItem(STORAGE_PREFIX + this.key);
      const parsed = text ? JSON.parse(text) : null;
      if (isSavedScan(parsed)) this.saved = parsed;
    } catch {
      // An unreadable saved scan is read again from the chain.
    }
    this.loaded = true;
    this.changed(false);
  }

  /** Brings the scan up to `height`: on the first run a search back from it, later the new blocks. */
  sync(height: number): Promise<void> {
    return this.run(async () => {
      await this.load();
      if (!this.saved) {
        this.saved = emptyScan(height);
        await this.older(SEARCH_ITEMS);
        this.synced = height;
        return;
      }
      if (height !== this.synced) await this.newer(height);
      this.synced = height;
    });
  }

  /** Reads further back, until it finds some more activity or has read a search's worth of blocks. */
  searchOlder(): Promise<void> {
    return this.run(async () => {
      await this.load();
      if (this.saved) await this.older(SEARCH_ITEMS);
    });
  }

  private async newer(height: number) {
    let scan = this.saved!;
    if (height < scan.high) scan = rollBack(scan, height - REORG_DEPTH);
    // Reading from the last block read shows whether the tip changed under it.
    let from = scan.tip ? scan.high : scan.high + 1;
    let moved = scan !== this.saved;
    while (from <= height) {
      const blocks = await this.node.events(from, PAGE);
      if (blocks.length === 0) break;
      if (scan.tip && blocks[0].height === scan.high && blocks[0].block !== scan.tip) {
        scan = rollBack(scan, scan.high - REORG_DEPTH);
        from = scan.high + 1;
        moved = true;
        continue;
      }
      scan = addNewer(scan, blocks, this.scope);
      from = blocks[blocks.length - 1].height + 1;
      moved = true;
    }
    if (!moved) return;
    this.saved = scan;
    this.changed(true);
  }

  private async older(wanted: number) {
    this.searching = true;
    this.changed(false);
    try {
      let found = 0;
      let budget = SEARCH_BLOCKS;
      while (this.saved!.low > 1 && found < wanted && budget > 0) {
        const to = this.saved!.low - 1;
        const from = Math.max(1, to - PAGE * PARALLEL + 1);
        const blocks = await readRange(this.node, from, to);
        if (blocks.length === 0) break;
        const before = this.saved!.items.length;
        this.saved = addOlder(this.saved!, blocks, this.scope);
        found += this.saved.items.length - before;
        budget -= to - from + 1;
        this.changed(true);
      }
    } finally {
      this.searching = false;
      this.changed(false);
    }
  }
}

const scans = new Map<string, Scan>();

/** Everything was deleted: no scan saves again, and each is begun anew when next shown. */
export function forgetScans() {
  for (const scan of scans.values()) scan.dropped = true;
  scans.clear();
}

function scanFor(scope: ScanScope, node: ChainApi): Scan {
  const key = scopeKey(scope);
  let scan = scans.get(key);
  if (!scan) {
    scan = new Scan(key, scope, node);
    scans.set(key, scan);
  }
  scan.node = node;
  return scan;
}

/** The scan of `scope`, kept up to the chain's `height`, and its found activity; null without a scope. */
export function useScan(height: number | undefined, scope: ScanScope | null): Scan | null {
  const node = useNodeClient();
  const key = scope ? scopeKey(scope) : null;
  const scan = useMemo(() => (scope ? scanFor(scope, node) : null), [key, node]); // eslint-disable-line react-hooks/exhaustive-deps
  const [, setVersion] = useState(0);
  useEffect(() => (scan ? scan.subscribe(() => setVersion(scan.version)) : undefined), [scan]);
  useEffect(() => {
    if (scan && height) scan.sync(height);
  }, [scan, height]);
  return scan;
}

/** What one list shows of the activity: the coins it is about, the accounts, and whether identity events belong. */
export interface ActivityFilter {
  coins: Uint8Array[];
  /** Only events touching these accounts; every account of the scope when absent. */
  accounts?: Uint8Array[];
  identity: boolean;
}

export interface Activity {
  /** Shown so far, newest first. */
  items: ActivityItem[];
  /** Nothing found yet while the first search is still reading. */
  loading: boolean;
  /** Reading older blocks now. */
  searching: boolean;
  /** Found but not shown yet. */
  hidden: number;
  /** The oldest block read so far, or null once the search has reached the start of the chain. */
  searchedTo: number | null;
  /** Shows the next items already found. */
  showMore: () => void;
  /** Reads older blocks for more activity. */
  searchOlder: () => void;
}

/** A list's view of the user's activity: the newest first, more as the user scrolls, older blocks read on request. */
export function useActivity(height: number | undefined, scope: ScanScope | null, filter: ActivityFilter): Activity {
  const scan = useScan(height, scope);
  const [shown, setShown] = useState(SHOWN);
  const coins = new Set(filter.coins.map(hex));
  const all = (scan?.activity ?? []).filter((item) => {
    if (item.identity) return filter.identity;
    if (!item.coin || !coins.has(hex(item.contract))) return false;
    return !filter.accounts || touches(item.coin, filter.accounts, []);
  });
  const low = scan?.saved?.low ?? null;
  return {
    items: all.slice(0, shown),
    loading: !!scope && (!scan?.loaded || (!!scan?.searching && all.length === 0) || (!scan?.saved && !!height)),
    searching: !!scan?.searching,
    hidden: Math.max(0, all.length - shown),
    searchedTo: low !== null && low > 1 ? low : null,
    showMore: () => setShown((n) => (n < all.length ? n + SHOWN : n)),
    searchOlder: () => {
      if (!scan) return;
      setShown((n) => Math.max(n, all.length + SHOWN));
      scan.searchOlder();
    },
  };
}
