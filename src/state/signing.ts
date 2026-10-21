// Answering a site's signing request (SIGNING.md): fetching it, checking it
// against this device's chain and users, and sending the signed answer, or the
// refusal, back to the site.
import { hex } from '@/chain/bytes';
import type { Canonical } from '@/chain/contracts';
import { accountWitness, signerAccount } from '@/chain/fees';
import { shortId, nowSeconds } from '@/chain/format';
import { siteFetch } from '@/chain/node';
import { OffchainError, SignRequest, answerJson, declineJson, isAttachment, readRequest, signedMessage } from '@/chain/offchain';
import { IdentityInfo, resolveSigner } from './chain';
import type { KeyMeta, User } from './types';

const TIMEOUT_MS = 30_000;
/** A request or a site's reply larger than this is not one. */
const MAX_REPLY = 256 * 1024;
/** The largest file a statement can cover. */
const MAX_FILE = 25 * 1024 * 1024;

/**
 * A site is asked as a stranger: no cookies go with the request and none are
 * kept, so one site's answers cannot follow the person to another.
 */
const STRANGER: RequestInit = { credentials: 'omit' };

/** The body of `res`, refused unread when it says it is larger than `max`, and refused once read past it. */
async function bodyOf(res: Response, max: number, what: string): Promise<Uint8Array> {
  const length = Number(res.headers.get('content-length') ?? '');
  if (Number.isFinite(length) && length > max) throw new OffchainError(`the site sent ${what} far too large`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  if (bytes.length > max) throw new OffchainError(`the site sent ${what} far too large`);
  return bytes;
}

async function exchange(url: string, init: RequestInit = {}): Promise<{ ok: boolean; status: number; json: unknown }> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  try {
    const res = await siteFetch()(url, { ...STRANGER, ...init, signal: abort.signal, headers: { accept: 'application/json', ...(init.headers ?? {}) } });
    if (res.redirected) throw new OffchainError('the site sent the request on to another address, which Ting ID does not follow');
    const text = new TextDecoder().decode(await bodyOf(res, MAX_REPLY, 'an answer'));
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { ok: res.ok, status: res.status, json };
  } catch (e) {
    if (e instanceof OffchainError) throw e;
    throw new OffchainError(abort.signal.aborted ? 'the site did not answer in time' : 'the site could not be reached');
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The site's own words, one line, marked as the site's so they never read as
 * the app's; `fallback`, the app's, when the site gave none.
 */
function siteMessage(json: unknown, fallback: string): string {
  const m = (json as { message?: unknown } | null)?.message;
  if (typeof m !== 'string' || !m.trim()) return fallback;
  const line = m.replace(/\s+/g, ' ').trim();
  return `the site says: “${line.length > 240 ? `${line.slice(0, 240)}…` : line}”`;
}

/** Fetches the request at `url` and checks it before anything of it is shown. */
export async function fetchSignRequest(url: string): Promise<SignRequest> {
  const r = await exchange(url);
  if (!r.ok) throw new OffchainError(siteMessage(r.json, `the site answered ${r.status}`));
  return readRequest(r.json, url, nowSeconds());
}

/**
 * The file `request`'s statement covers, fetched from the site and checked
 * against the hash the statement signs: only that exact file comes back.
 */
export async function fetchAttachment(request: SignRequest): Promise<Uint8Array> {
  const doc = request.request.request;
  if (doc.kind !== 'statement' || !doc.attachment || !request.file) throw new OffchainError('this request covers no file');
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  let bytes: Uint8Array;
  try {
    const res = await siteFetch()(request.file, { ...STRANGER, signal: abort.signal });
    if (res.redirected) throw new OffchainError('the site sent the file request on to another address, which Ting ID does not follow');
    if (!res.ok) throw new OffchainError(`the site answered ${res.status} for the file`);
    bytes = await bodyOf(res, MAX_FILE, 'a file');
  } catch (e) {
    if (e instanceof OffchainError) throw e;
    throw new OffchainError(abort.signal.aborted ? 'the site did not send the file in time' : 'the file could not be fetched');
  } finally {
    clearTimeout(timer);
  }
  if (!isAttachment(bytes, doc.attachment)) throw new OffchainError('the site sent another file than the one the statement covers');
  return bytes;
}

/**
 * Why this device cannot answer `request` with `user` on chain `chainHex`, or
 * null when it can: the request must be for the chain the app uses, and for
 * the ID it names, when it names one.
 */
export function signerProblem(request: SignRequest, user: User | null, chainHex: string | null): string | null {
  if (!chainHex) return 'the app is not connected to a node';
  if (hex(request.chainId) !== chainHex) return 'this request is for another Ting network than the one this app uses';
  if (!user) return 'create a user first';
  if (request.signer) {
    if (user.kind !== 'identity' || user.identityId !== hex(request.signer)) return `this request is for the ID ${shortId(request.signer, 8, 6)}`;
  }
  if (user.kind === 'identity' && user.chainId && user.chainId !== chainHex) return 'this user belongs to another Ting network';
  return null;
}

/** The users of this device that may answer: the one the request names, or every user of this chain. */
export function possibleSigners(request: SignRequest, users: User[], chainHex: string | null): User[] {
  return users.filter((u) => signerProblem(request, u, chainHex) === null);
}

/**
 * Signs `request` as `user` (an identity signs with its spending account) and
 * sends the answer. Returns what the site said; throws when the site refused.
 */
export async function signAndAnswer(request: SignRequest, user: User, c: Canonical, keys: KeyMeta[], identity: IdentityInfo | null): Promise<string> {
  const signer = await resolveSigner(user, 0, c, keys, identity);
  const account = signerAccount(signer);
  const witness = accountWitness(signer, signedMessage(request, account));
  const body = answerJson({ nonce: request.nonce, account, policy: signer.policy, params: signer.params, witness });
  const r = await exchange(request.callback, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const message = siteMessage(r.json, r.ok ? 'Done.' : `the site refused the answer (${r.status})`);
  if (!r.ok) throw new OffchainError(message);
  return message;
}

/** Tells the site the user declined, so it stops waiting; a site that cannot be reached does not matter here. */
export async function declineRequest(request: SignRequest): Promise<void> {
  await exchange(request.callback, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(declineJson(request.nonce)) }).catch(() => {});
}
