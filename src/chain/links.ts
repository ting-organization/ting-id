// Ting links (LINKS.md): what one phone asks of another, as a QR code, as
// copied text, or tapped on the phone, which opens Ting ID.
//
//   ting:<request>?<field>=<value>&…
//
//   pay      net, to, coin?, amount?, note?     pay an account
//   create   net, input, name?, coin?, amount?  create an identity, and send it coins
//   device   net, id, scheme, key               add a device to an identity
//   recover  net, id, keys                      approve a recovery to new keys
//   sign     url                                a site's signing request (SIGNING.md)
//
// 32-byte IDs and accounts are lowercase hex, longer bytes base64url without
// padding, amounts decimal in the coin's units, text percent-encoded. `net`
// is the first 8 hex digits of the chain ID, so a link for another network is
// refused. The scheme, the request and field names are read without regard to
// case, a `//` after the scheme is tolerated, and unknown fields are ignored.
import { base64url, fromBase64url, hex, isHex, unhex } from './bytes';
import { SCHEMES, Scheme, isPublicKey } from './keys';

export type Link =
  | { request: 'pay'; net: string; to: string; coin?: string; amount?: string; note?: string }
  | { request: 'create'; net: string; input: Uint8Array; name?: string; coin?: string; amount?: string }
  | { request: 'device'; net: string; id: string; scheme: Scheme; key: Uint8Array }
  | { request: 'recover'; net: string; id: string; keys: Uint8Array }
  | { request: 'sign'; url: string };

export type LinkRequest = Link['request'];

/** Why a text is no link this app can follow. */
export class LinkError extends Error {}

export const LINK_SCHEME = 'ting:';
const REQUESTS: LinkRequest[] = ['pay', 'create', 'device', 'recover', 'sign'];
/** The longest name or note a link carries. */
export const LINK_TEXT_MAX = 80;
/**
 * Characters a name or note may not hold: controls and line breaks, marks
 * that turn the direction of text, and invisible ones, any of which could
 * make a name read as another.
 */
const HIDING = /[\u0000-\u001f\u007f-\u009f\u061c\u200b-\u200f\u2028-\u202e\u2060-\u2069\ufeff]/;

/** The network tag of a chain: the first 8 hex digits of its ID. */
export function netOf(chainId: Uint8Array): string {
  return hex(chainId).slice(0, 8);
}

/** Whether `text` is written as a Ting link at all. */
export function isLink(text: string): boolean {
  return text.trim().toLowerCase().startsWith(LINK_SCHEME);
}

const enc = encodeURIComponent;

export function encodeLink(link: Link): string {
  const fields: [string, string | undefined][] = (() => {
    switch (link.request) {
      case 'pay':
        return [['to', link.to], ['coin', link.coin], ['amount', link.amount], ['note', link.note], ['net', link.net]];
      case 'create':
        return [['input', base64url(link.input)], ['name', link.name], ['coin', link.coin], ['amount', link.amount], ['net', link.net]];
      case 'device':
        return [['id', link.id], ['scheme', link.scheme], ['key', hex(link.key)], ['net', link.net]];
      case 'recover':
        return [['id', link.id], ['keys', base64url(link.keys)], ['net', link.net]];
      case 'sign':
        return [['url', link.url]];
    }
  })();
  const query = fields.filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => `${k}=${enc(v!)}`);
  return `${LINK_SCHEME}${link.request}?${query.join('&')}`;
}

/** The link `text` holds; throws LinkError, saying what is wrong, when it holds none this app follows. */
export function parseLink(text: string): Link {
  let rest = text.trim();
  if (!isLink(rest)) throw new LinkError('this is not a Ting link');
  rest = rest.slice(LINK_SCHEME.length);
  if (rest.startsWith('//')) rest = rest.slice(2);
  const q = rest.indexOf('?');
  const request = (q < 0 ? rest : rest.slice(0, q)).replace(/\/$/, '').toLowerCase();
  if (!REQUESTS.includes(request as LinkRequest)) throw new LinkError('this Ting link asks for something this version of Ting ID does not know');
  const fields = new Map<string, string>();
  for (const part of q < 0 ? [] : rest.slice(q + 1).split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    const key = (eq < 0 ? part : part.slice(0, eq)).toLowerCase();
    let value: string;
    try {
      value = decodeURIComponent((eq < 0 ? '' : part.slice(eq + 1)).replace(/\+/g, ' '));
    } catch {
      throw new LinkError(`the link’s ${key} is not written right`);
    }
    if (fields.has(key)) throw new LinkError(`the link gives ${key} twice`);
    fields.set(key, value);
  }
  const need = (key: string) => {
    const v = fields.get(key);
    if (v === undefined || v === '') throw new LinkError(`the link has no ${key}`);
    return v;
  };
  const id32 = (key: string, v: string) => {
    if (!isHex(v, 32)) throw new LinkError(`the link’s ${key} is not 64 hex digits`);
    return v.toLowerCase();
  };
  const bytes = (key: string) => {
    const b = fromBase64url(need(key));
    if (!b || b.length === 0) throw new LinkError(`the link’s ${key} is not written right`);
    return b;
  };
  const optional = <T>(key: string, read: (v: string) => T): T | undefined => {
    const v = fields.get(key);
    return v === undefined || v === '' ? undefined : read(v);
  };
  const amount = (v: string) => {
    if (!/^\d+(\.\d+)?$/.test(v)) throw new LinkError('the link’s amount is not a number like 12.5');
    return v;
  };
  const textField = (key: string) => (v: string) => {
    if (v.length > LINK_TEXT_MAX) throw new LinkError(`the link’s ${key} is longer than ${LINK_TEXT_MAX} characters`);
    if (HIDING.test(v)) throw new LinkError(`the link’s ${key} holds characters that could hide or reorder what it says`);
    return v.trim();
  };
  if (request === 'sign') return { request, url: need('url') };
  const net = need('net').toLowerCase();
  if (!/^[0-9a-f]{8}$/.test(net)) throw new LinkError('the link’s network is not 8 hex digits');
  switch (request as Exclude<LinkRequest, 'sign'>) {
    case 'pay':
      return {
        request: 'pay',
        net,
        to: id32('account', need('to')),
        coin: optional('coin', (v) => id32('coin', v)),
        amount: optional('amount', amount),
        note: optional('note', textField('note')),
      };
    case 'create':
      return {
        request: 'create',
        net,
        input: bytes('input'),
        name: optional('name', textField('name')),
        coin: optional('coin', (v) => id32('coin', v)),
        amount: optional('amount', amount),
      };
    case 'device': {
      const scheme = need('scheme').toLowerCase() as Scheme;
      if (!SCHEMES.includes(scheme)) throw new LinkError('the link names a key scheme this app does not know');
      const key = need('key');
      if (!isHex(key, 65) || !isPublicKey(scheme, unhex(key.toLowerCase()))) throw new LinkError('the link’s key is not a public key');
      return { request: 'device', net, id: id32('identity ID', need('id')), scheme, key: unhex(key.toLowerCase()) };
    }
    case 'recover':
      return { request: 'recover', net, id: id32('identity ID', need('id')), keys: bytes('keys') };
  }
}

/** The link in `text` when it is one of `request`; throws LinkError otherwise. */
export function parseLinkOf<R extends LinkRequest>(text: string, request: R): Extract<Link, { request: R }> {
  const link = parseLink(text);
  if (link.request !== request) throw new LinkError(`this link is a ${link.request} link, not a ${request} link`);
  return link as Extract<Link, { request: R }>;
}

/** The account `text` names: a bare 64-digit account, or the account a pay link pays; null otherwise. */
export function accountIn(text: string): string | null {
  const t = text.trim();
  if (isHex(t, 32)) return t.toLowerCase();
  if (!isLink(t)) return null;
  try {
    const link = parseLink(t);
    return link.request === 'pay' ? link.to : null;
  } catch {
    return null;
  }
}
