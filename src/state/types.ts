import type { Scheme } from '@/chain/keys';

/** A signing key held on this device; its secret lives in the keystore. */
export interface KeyMeta {
  id: string;
  name: string;
  scheme: Scheme;
  /** Uncompressed SEC1 public key, hex. */
  pub: string;
  createdAt: number;
}

/**
 * A user of this device: an identity (wiki: programs/identity) with its slots,
 * or a single key acting as its own account (a policy account).
 */
export interface User {
  id: string;
  name: string;
  color: string;
  kind: 'identity' | 'key';
  /** Identity users: the id, its registry and chain, and the creation input (hex) when it was made here. */
  identityId?: string;
  registry?: string;
  chainId?: string;
  creation?: string;
  /**
   * The key this device uses for the user: the account itself for key users;
   * for identities, the key this device made for it (one of its keys, or the
   * one waiting to be added by another device or a recovery).
   */
  keyId?: string;
  /**
   * How this phone keeps the user's recovery phrase (state/phrases.ts):
   * behind the keystore's own fingerprint or face check, or behind the
   * phone's unlock asked for by the app. Absent when it keeps none.
   */
  phrase?: 'biometric' | 'device';
  /** The key the recovery phrase gives (a KeyMeta id), which the identity lists or a change will add. */
  phraseKeyId?: string;
  /** The person confirmed the words by typing some of them back. */
  phraseBackedUp?: boolean;
  /** New words were made to be added to the identity by a change that is not proposed yet. */
  phraseToAdd?: boolean;
  /** Guardians this phone proposed for the identity (IDs, hex), so it can name and unlink them later. */
  knownGuardians?: string[];
}

export interface Contact {
  id: string;
  name: string;
  /** 32-byte account, hex. */
  account: string;
  /** The chain it was saved on (hex): an account is only meaningful on its own chain. */
  chain?: string;
}

export interface Settings {
  /** The nodes the app uses, as one (chain/pool), in the user's order. */
  nodeUrls: string[];
  /** Coins the user follows, hex contract ids, in display order. None is special. */
  coins: string[];
  /** Discovered coins the user chose not to follow. */
  dismissed: string[];
  /** Shows keys, signature schemes and other technical detail. Off for most people. */
  advanced: boolean;
  /** The phone app's coin tools: every coin on the chain, creating coins and minting. Off unless switched on. */
  coinTools?: boolean;
  /**
   * The coin fees are paid in (hex contract id), while followed; null for the
   * chain's listed coin. Miners rank transactions by what they pay in coins
   * they accept, so one widely accepted coin pays for operations in any coin.
   */
  feeCoin: string | null;
  /** Light or dark, or as the system is set. */
  theme: Theme;
  /** The phone checks its owner when Ting ID opens or comes back after a while (ui/AppLock.tsx). On unless turned off. */
  unlockApp?: boolean;
  /** The phone checks its owner before a payment goes. On unless turned off. */
  confirmPayments?: boolean;
}

export type Theme = 'system' | 'light' | 'dark';

/** A transaction this device submitted: what it does, and what became of it. */
export interface SubmittedTx {
  id: string;
  at: number;
  summary: string;
  userId: string;
  /** The chain it was sent to (hex), so it is only followed there. */
  chain?: string;
  /**
   * Followed until settled: `pending` while no block has included it,
   * `included` once one has, `failed` when it ran in a block without
   * succeeding or the node refused it again, `dismissed` once the user put it
   * away. Entries from before this was tracked have none and are not followed.
   */
  state?: 'pending' | 'included' | 'failed' | 'dismissed';
  /** The block that included it. */
  height?: number;
  /** Why it failed. */
  problem?: string;
  /** For a payment: the coin, the paying account (hex) and the amount in base units, for the account's "on the way". */
  coin?: string;
  account?: string;
  amount?: string;
  /** For a payment: the account it goes to (hex). */
  to?: string;
}

/** A submitted transaction's bytes, kept until a block includes it, to send again if the node loses it. */
export interface OutboxEntry {
  raw: string;
  notBefore?: number;
  at: number;
  /** For a batch that pays for an operation: the operation sent alone, whichever of the two goes in. */
  twin?: string;
}

/** A site's signing request this device answered (SIGNING.md), kept so the user can see what they signed. */
export interface SignatureNote {
  at: number;
  userId: string;
  origin: string;
  kind: 'signIn' | 'grant' | 'statement';
  title: string;
  outcome: 'signed' | 'declined' | 'failed';
  /** What the site answered, or why it failed. */
  message: string | null;
}
