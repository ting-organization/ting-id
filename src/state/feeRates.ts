// The rates recent transactions paid (chain/feeMarket), read from the node
// for the chain the app is on: back from the tip until the last `SAMPLE`
// paying transactions are found, at most 3,000 blocks, then each new block as
// it comes. One reading serves every screen.
import { useEffect, useState } from 'react';
import { hex } from '@/chain/bytes';
import { Rate, SAMPLE, ratesOf } from '@/chain/feeMarket';
import type { ChainApi } from '@/chain/node';

/** How far back paying transactions are looked for. */
const REACH = 3000;
/** Blocks per request, the node's most. */
const PAGE = 100;

/** The blocks `from` to `to` are read; `ready` once the newest are, as older ones keep coming. */
let cache: { chain: string; from: number; to: number; rates: Rate[]; ready: boolean } | null = null;
let reading: Promise<void> | null = null;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

async function read(node: ChainApi, chain: string, height: number): Promise<void> {
  if (cache?.chain !== chain) cache = { chain, from: height + 1, to: height, rates: [], ready: false };
  const c = cache;
  // The blocks since the last reading.
  for (let from = c.to + 1; from <= height; ) {
    const blocks = await node.blocks(from, Math.min(PAGE, height - from + 1));
    if (blocks.length === 0) break;
    c.rates.push(...ratesOf(blocks));
    from = blocks[blocks.length - 1].height + 1;
    c.to = from - 1;
  }
  if (c.ready) notify();
  // Older ones, until there are enough paying transactions.
  while (c.rates.length < SAMPLE && c.from > 1 && height - c.from + 1 < REACH) {
    const start = Math.max(1, c.from - PAGE);
    c.rates.push(...ratesOf(await node.blocks(start, c.from - start)));
    c.from = start;
    c.ready = true;
    notify();
  }
  c.rates = c.rates.filter((r) => r.height > height - REACH);
  c.ready = true;
}

/** The rates of recent paying transactions; null until first read. */
export function useFeeRates(node: ChainApi, chainId: Uint8Array | null, height: number | null): Rate[] | null {
  const [, setRead] = useState(0);
  const chain = chainId ? hex(chainId) : null;
  useEffect(() => {
    if (!chain || height === null) return;
    const heard = () => setRead((n) => n + 1);
    listeners.add(heard);
    if (!reading && (cache?.chain !== chain || cache.to < height)) {
      reading = read(node, chain, height)
        .catch(() => {})
        .finally(() => {
          reading = null;
          notify();
        });
    }
    return () => {
      listeners.delete(heard);
    };
  }, [node, chain, height]);
  return chain && cache?.chain === chain && cache.ready ? cache.rates : null;
}
