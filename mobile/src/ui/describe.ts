// Words for on-chain things: amounts in display units, accounts by the names
// this device knows, effects and events in plain language.
import { hex } from '@/chain/bytes';
import * as coin from '@/chain/coin';
import { formatAmount, formatTime, nowSeconds, relativeTime, shortId } from '@/chain/format';
import type { Request } from '@/chain/offchain';
import * as idp from '@/chain/identity';
import { CONFIRMATIONS, NodeHealth, PoolNotice } from '@/chain/pool';
import type { ActivityItem } from '@/state/chain';
import type { CoinInfo } from '@/state/coins';
import type { Watch } from '@/state/watch';
import type { IconName, Tone } from './components';

/** Account (hex) → label, built from users, their slots and contacts. */
export type Names = Map<string, string>;

export function nameOf(names: Names, account: Uint8Array): string {
  return names.get(hex(account)) ?? shortId(account, 8, 6);
}

export function amount(c: CoinInfo | null, value: bigint): string {
  if (!c) return value.toString();
  return `${formatAmount(value, c.config.decimals)} ${c.config.symbol}`;
}

export function describeEffect(e: coin.Effect, c: CoinInfo | null, names: Names): string {
  switch (e.kind) {
    case 'transfer':
      return `Send ${amount(c, e.amount)} to ${nameOf(names, e.to)}`;
    case 'burn':
      return `Burn ${amount(c, e.amount)}`;
    case 'mint':
      return `Mint ${amount(c, e.amount)} to ${nameOf(names, e.to)}`;
    case 'setMinter':
      return `Set minter to ${nameOf(names, e.minter)}`;
  }
}

/** A short fingerprint of a key: its parameters, abbreviated. */
export function keyFingerprint(key: idp.IdKey): string {
  return shortId(key.params, 8, 4);
}

/** The keys a recovery installs, e.g. "1 key: 04a1b2c3…d4e5". */
export function describeKeys(keys: idp.IdKey[]): string {
  return `${keys.length} key${keys.length === 1 ? '' : 's'}: ${keys.map(keyFingerprint).join(', ')}`;
}

/** A change to an identity in words; without `advanced`, keys are the devices that hold them. */
export function describeOperation(op: idp.Operation, names: Names, advanced = true): string {
  switch (op.kind) {
    case 'addKey':
      return advanced ? `Add key ${keyFingerprint(op.key)}` : 'Add a device';
    case 'removeKey':
      return advanced ? `Remove key ${op.keyId}` : 'Remove a device';
    case 'addGuardian':
      return `Link guardian ${nameOf(names, op.id)}`;
    case 'removeGuardian':
      return `Unlink guardian ${nameOf(names, op.id)}`;
    case 'setThreshold':
      return op.threshold === 0n ? 'Recovery needs a majority of guardians' : `Recovery needs ${count(op.threshold, 'guardian')}`;
    case 'setDelays':
      return `Delays: changes ${relativeDuration(op.changeDelay)}, recovery ${relativeDuration(op.recoveryDelay)}`;
    case 'setSlot':
      return `Account ${op.slot}: ${op.delay === 0n ? 'instant' : lockOf(op.delay)}`;
  }
}

/** A message as a sentence: capitalized, with a full stop at the end. */
export function sentence(message: string): string {
  const t = message.trim();
  if (!t) return t;
  const s = t.charAt(0).toUpperCase() + t.slice(1);
  return /[.!?…]$/.test(s) ? s : `${s}.`;
}

/** `1 guardian`, `3 guardians`. */
export function count(n: bigint | number, noun: string, nouns = `${noun}s`): string {
  return `${n} ${BigInt(n) === 1n ? noun : nouns}`;
}

/** A span of time in words: `45 minutes`, `1 hour`, `7 days`. */
export function relativeDuration(seconds: bigint | number): string {
  const s = Number(seconds);
  if (s === 0) return 'none';
  if (s < 3600) return count(Math.round(s / 60), 'minute');
  if (s < 86_400) return `${+(s / 3600).toFixed(1)} ${s === 3600 ? 'hour' : 'hours'}`;
  return `${+(s / 86_400).toFixed(1)} ${s === 86_400 ? 'day' : 'days'}`;
}

/** An account's lock as a noun: `7-day lock`, `12-hour lock`. */
export function lockOf(seconds: bigint | number): string {
  const s = Number(seconds);
  if (s < 3600) return `${Math.round(s / 60)}-minute lock`;
  if (s < 86_400) return `${+(s / 3600).toFixed(1)}-hour lock`;
  return `${+(s / 86_400).toFixed(1)}-day lock`;
}

/** When a scheduled payment runs: after the chosen wait and the account's lock, whichever ends later. */
export function runsIn(wait: number, lock: number): string {
  if (!lock) return `Runs in ${relativeDuration(wait)}`;
  if (!wait) return `Runs in ${relativeDuration(lock)}, once the account's ${lockOf(lock)} has passed`;
  return `Runs in ${relativeDuration(Math.max(wait, lock))}, the later of the chosen ${relativeDuration(wait)} and the account's ${lockOf(lock)}`;
}

export interface Described {
  title: string;
  detail?: string;
  tone: Tone;
  icon: IconName;
  /** Signed amount for the right column, e.g. "+5 DLR". */
  amount?: string;
  amountTone?: Tone;
  /** Who or what it was with: the other account, or the coin itself for what it issued or burned. */
  party?: { name: string; coin?: boolean };
  /** The action in a few words, for a line under the party. */
  short?: string;
  /** What a scheduled payment will move once it runs. */
  planned?: string;
  /** The fees its transaction paid, e.g. "0.0001 DLR". */
  fee?: string;
}

/** An activity item in words; a fee its transaction paid joins the detail. */
export function describeActivity(item: ActivityItem, coins: Map<string, CoinInfo>, names: Names, mine: Uint8Array[]): Described {
  const d = describeEvent(item, coins, names, mine);
  if (!item.fees?.length) return d;
  const fee = item.fees.map((f) => amount(coins.get(hex(f.contract)) ?? null, f.amount)).join(' + ');
  return { ...d, fee, detail: d.detail ? `${d.detail} · fee ${fee}` : `fee ${fee}` };
}

function describeEvent(item: ActivityItem, coins: Map<string, CoinInfo>, names: Names, mine: Uint8Array[]): Described {
  const isMine = (a: Uint8Array) => mine.some((m) => hex(m) === hex(a));
  if (item.coin) {
    const e = item.coin;
    const c = coins.get(hex(item.contract)) ?? null;
    const coinParty = { name: c?.config.name ?? 'Coin', coin: true };
    switch (e.kind) {
      case 'transfer': {
        const zeroFrom = hex(e.from) === hex(coin.ZERO_ACCOUNT);
        const zeroTo = hex(e.to) === hex(coin.ZERO_ACCOUNT);
        const value = amount(c, e.amount);
        if (zeroFrom)
          return {
            title: 'Received',
            detail: 'issued by the coin',
            tone: 'success',
            icon: 'arrow-down',
            amount: `+${value}`,
            amountTone: 'success',
            party: coinParty,
            short: `Issued to ${nameOf(names, e.to)}`,
          };
        if (zeroTo)
          return { title: 'Burned', detail: `from ${nameOf(names, e.from)}`, tone: 'neutral', icon: 'flame-outline', amount: `−${value}`, party: coinParty, short: `Burned from ${nameOf(names, e.from)}` };
        if (item.fee && isMine(e.from)) return { title: 'Fee', detail: `from ${nameOf(names, e.from)} to the block producer`, tone: 'neutral', icon: 'hammer-outline', amount: `−${value}` };
        if (isMine(e.to) && !isMine(e.from))
          return {
            title: 'Received',
            detail: `from ${nameOf(names, e.from)}`,
            tone: 'success',
            icon: 'arrow-down',
            amount: `+${value}`,
            amountTone: 'success',
            party: { name: nameOf(names, e.from) },
            short: 'Received',
          };
        if (isMine(e.from) && !isMine(e.to))
          return { title: 'Sent', detail: `to ${nameOf(names, e.to)}`, tone: 'primary', icon: 'arrow-up', amount: `−${value}`, party: { name: nameOf(names, e.to) }, short: 'Sent' };
        return { title: 'Moved', detail: `${nameOf(names, e.from)} → ${nameOf(names, e.to)}`, tone: 'neutral', icon: 'swap-horizontal', amount: value };
      }
      case 'scheduled': {
        const tip = e.tip > 0n ? ` · run fee ${amount(c, e.tip)}` : '';
        const to = e.effect.kind === 'transfer' || e.effect.kind === 'mint' ? { name: nameOf(names, e.effect.to) } : undefined;
        const planned = e.effect.kind === 'transfer' ? amount(c, e.effect.amount) : undefined;
        if (item.settled === 'executed')
          return { title: 'Scheduled', detail: `${describeEffect(e.effect, c, names)} · ran${tip}`, tone: 'neutral', icon: 'checkmark-circle-outline', party: to, short: 'Scheduled · ran', planned };
        if (item.settled === 'cancelled')
          return {
            title: 'Scheduled',
            detail: `${describeEffect(e.effect, c, names)} · cancelled`,
            tone: 'neutral',
            icon: 'close-circle-outline',
            party: to,
            short: 'Scheduled · cancelled',
            planned,
          };
        return {
          title: 'Scheduled',
          detail: `${describeEffect(e.effect, c, names)} · runs ${relativeTime(e.executeAt)}${tip}`,
          tone: 'warning',
          icon: 'time-outline',
          party: to,
          short: `Scheduled · runs ${relativeTime(e.executeAt)}`,
          planned,
        };
      }
      case 'executed':
        return { title: 'Ran', detail: `scheduled action #${e.sequence} of ${nameOf(names, e.account)}`, tone: 'success', icon: 'play-outline' };
      case 'cancelled':
        return { title: 'Cancelled', detail: `scheduled action #${e.sequence} of ${nameOf(names, e.account)}`, tone: 'neutral', icon: 'close-circle-outline' };
      case 'producerPayment':
        return { title: 'Producer payment', detail: `to ${nameOf(names, e.payee)}`, tone: 'neutral', icon: 'hammer-outline', amount: amount(c, e.amount) };
      case 'minter':
        return { title: 'Minter changed', detail: nameOf(names, e.minter), tone: 'neutral', icon: 'key-outline' };
    }
  }
  const e = item.identity!;
  switch (e.kind) {
    case 'created':
      return { title: 'Identity created', detail: nameOf(names, e.id), tone: 'accent', icon: 'finger-print-outline' };
    case 'changeProposed':
      return {
        title: `Change #${e.changeNo} proposed`,
        detail: `${e.operations.map((o) => describeOperation(o, names)).join('; ')} — applies ${relativeTime(e.effectiveAt)}`,
        tone: 'warning',
        icon: 'hourglass-outline',
      };
    case 'changeCancelled':
      return { title: `Change #${e.changeNo} cancelled`, tone: 'neutral', icon: 'close-circle-outline' };
    case 'changeApplied':
      return { title: `Change #${e.changeNo} applied`, tone: 'accent', icon: 'checkmark-circle-outline' };
    case 'recoveryApproved':
      return {
        title: `Recovery of ${nameOf(names, e.id)} approved`,
        detail: `by ${nameOf(names, e.guardian)} · ${e.approvals} approval${e.approvals === 1n ? '' : 's'}${
          e.reachedAt > 0n ? ', threshold reached: pending changes voided' : ''
        } · installs ${describeKeys(e.keys)}`,
        tone: 'warning',
        icon: 'shield-checkmark-outline',
      };
    case 'recoveryWithdrawn':
      return {
        title: `Recovery approval for ${nameOf(names, e.id)} withdrawn`,
        detail: `by ${nameOf(names, e.guardian)} · ${e.approvals} approval${e.approvals === 1n ? '' : 's'} left${e.reachedAt > 0n ? ', threshold still reached' : ''}`,
        tone: 'neutral',
        icon: 'shield-outline',
      };
    case 'recovered':
      return { title: 'Identity recovered', detail: 'keys replaced', tone: 'accent', icon: 'refresh-circle-outline' };
  }
}

// ---- Signing requests of sites -----------------------------------------------

/** A site as the app names it: the host, or the whole origin of one in development. */
export function siteName(origin: string): string {
  return origin.startsWith('https://') ? origin.slice('https://'.length) : origin;
}

/** Past this, a sign-in or grant gets a warning that it lasts long. */
const LONG = 90 * 86_400;

export interface DescribedRequest {
  icon: IconName;
  /** What signing does, in the app's words; the site is named apart, in the screen's header. */
  headline: string;
  detail: string | null;
  warning: string | null;
  /** The confirming button, named for what it does. */
  action: string;
}

/** What a site's request does when `signer` signs it, in the app's own words, never the site's. */
export function describeSignRequest(r: Request, signer: string): DescribedRequest {
  const lasting = (until: bigint) => {
    const left = Number(until) - nowSeconds();
    if (left <= 0) return { until: 'already over', warning: 'This has already run out; the site may not accept it.' };
    return { until: `${formatTime(until)} (${relativeTime(until)})`, warning: left > LONG ? `This lasts ${relativeDuration(left)}.` : null };
  };
  switch (r.kind) {
    case 'signIn': {
      const l = lasting(r.sessionUntil);
      return {
        icon: 'log-in-outline',
        headline: `Sign in as ${signer}`,
        detail: r.key ? `The browser that asked stays signed in until ${l.until}.` : `Signed in until ${l.until}.`,
        warning: l.warning,
        action: 'Sign in',
      };
    }
    case 'grant': {
      const l = lasting(r.until);
      return {
        icon: 'key-outline',
        headline: `Give access as ${signer}`,
        detail: `Until ${l.until}${r.key ? ', through a key the site holds' : ''}.`,
        warning: l.warning,
        action: 'Allow',
      };
    }
    case 'statement':
      return {
        icon: 'document-text-outline',
        headline: `Sign a statement as ${signer}`,
        detail: r.attachment ? 'It covers the attached file; open it before you sign.' : null,
        warning: null,
        action: 'Sign',
      };
  }
}

// ---- Submissions waiting for a block ------------------------------------------

/** Where a submitted transaction stands, in a line under its summary. */
export function describeWatch(watch: Watch, sentAt: number): { detail: string; tone: Tone; icon: IconName } {
  const sent = `sent ${relativeTime(sentAt)}`;
  switch (watch.kind) {
    case 'checking':
      return { detail: `Asking the node · ${sent}`, tone: 'neutral', icon: 'time-outline' };
    case 'waiting':
      return { detail: `Waiting for a block · ${sent}`, tone: 'warning', icon: 'time-outline' };
    case 'confirming':
      return { detail: `In block ${watch.height}; done once a few more blocks follow`, tone: 'neutral', icon: 'checkmark-outline' };
    case 'scheduled':
      return {
        detail: `Held by the node until ${formatTime(watch.notBefore)}${watch.lastResult ? ` · last try: ${watch.lastResult}` : ''}`,
        tone: 'warning',
        icon: 'time-outline',
      };
    case 'resent':
      return { detail: `The node had lost it; sent again · ${sent}`, tone: 'warning', icon: 'refresh-outline' };
    case 'lost':
      return { detail: 'The node no longer knows it, and this device has no copy to send again', tone: 'danger', icon: 'alert-circle-outline' };
    case 'failed':
      return { detail: `Not done: ${watch.reason}`, tone: 'danger', icon: 'close-circle-outline' };
  }
}

/** The one message while no node answers: screens keep showing what the nodes last reported. */
export function offlineMessage(height: number | null): string {
  return height !== null ? `Can't reach the node. Showing what it last reported, at block ${height}.` : "Can't reach the node. Check its address in Settings.";
}

/** The general message about the nodes, if any: none answers, or their answers did not check out. */
export function nodeNotice(height: number | null, error: string | null, notice: PoolNotice): string | null {
  if (error) return offlineMessage(height);
  if (notice?.kind === 'distrusted') return "A node gave answers that don't check out, so Ting ID stopped using it. See Settings → Nodes.";
  if (notice?.kind === 'split') return "Your nodes follow different chains, and Ting ID can't tell which is right. Check them in Settings → Nodes.";
  return null;
}

/** What an identity of another chain than the nodes' shows instead of its accounts. */
export const OTHER_CHAIN_USER =
  'This identity belongs to another Ting network, so it has no accounts here. Choose another user, or add that network’s nodes in Settings.';

/** The (i) text of Settings → Nodes. */
export const NODES_INFO =
  'Ting ID reads from the node furthest ahead and moves on to the next when one stops answering; transactions go to every node. It checks every balance and record against the block it comes from, and with two or more nodes, that the others have that block too and agree on each coin’s settings. Plain http is only for a node on this computer or your home network.';

/** One node's line in Settings → Nodes, and its tone; `bestHeight` is the height of the node in use. */
export function describeNode(h: NodeHealth, bestHeight: number | null): { text: string; tone: 'success' | 'muted' | 'warning' | 'danger' } {
  const block = h.status ? ` · block ${h.status.height}` : '';
  switch (h.state) {
    case 'ok':
      if (!h.best && h.status && bestHeight !== null && bestHeight - h.status.height > CONFIRMATIONS) return { text: `Catching up${block}`, tone: 'muted' };
      return { text: `${h.best ? 'In use' : 'Answering'}${block}`, tone: 'success' };
    case 'unknown':
      return { text: 'Checking…', tone: 'muted' };
    case 'down':
      return { text: 'Not answering', tone: 'warning' };
    case 'outdated':
      return { text: 'Needs an update: Ting ID cannot check its answers', tone: 'warning' };
    case 'other-chain':
      return { text: `On another chain${block}`, tone: 'warning' };
    case 'distrusted':
      return { text: `Not used: ${h.problem ?? 'its answers did not check out'}`, tone: 'danger' };
    case 'insecure':
      return { text: 'Not used: plain http is only for a node on this computer or your home network', tone: 'danger' };
  }
}
