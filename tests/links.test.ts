// Ting links (LINKS.md): written and read back, and refused when wrong.
import { secp256k1 } from '@noble/curves/secp256k1.js';
import { describe, expect, it } from 'vitest';
import { base64url, fromBase64url, hex } from '../src/chain/bytes';
import { LinkError, accountIn, encodeLink, netOf, parseLink, parseLinkOf } from '../src/chain/links';

const net = '26520bbe';
const account = 'ab'.repeat(32);
const daler = 'fed4bce86a8d48c0d854b040454d5c488837fbef890b676406db4a42fc64ad69';
const pub = secp256k1.getPublicKey(new Uint8Array(32).fill(7), false);

describe('base64url', () => {
  it('reads back what it writes, every length', () => {
    for (let n = 0; n < 40; n++) {
      const bytes = Uint8Array.from({ length: n }, (_, i) => (i * 37 + n) & 255);
      expect(hex(fromBase64url(base64url(bytes))!)).toBe(hex(bytes));
    }
    expect(base64url(Uint8Array.of(0xfb, 0xff))).toBe('-_8');
  });
  it('refuses padding, other letters, impossible lengths and stray bits', () => {
    expect(fromBase64url('-_8=')).toBeNull();
    expect(fromBase64url('ab+c')).toBeNull();
    expect(fromBase64url('abcde')).toBeNull();
    expect(fromBase64url('-_9')).toBeNull();
  });
});

describe('Ting links', () => {
  it('write and read each request', () => {
    const links = [
      { request: 'pay', net, to: account, coin: daler, amount: '12.5', note: 'Rent, March' },
      { request: 'pay', net, to: account },
      { request: 'create', net, input: Uint8Array.of(1, 2, 3, 250), name: 'Anna Ö', coin: daler, amount: '50' },
      { request: 'device', net, id: account, scheme: 'secp256k1', key: pub },
      { request: 'recover', net, id: account, keys: Uint8Array.of(9, 8, 7) },
      { request: 'sign', url: 'https://forum.example/request/1?x=1&y=2' },
    ] as const;
    for (const link of links) {
      const text = encodeLink(link);
      expect(text.startsWith(`ting:${link.request}?`)).toBe(true);
      const back = parseLink(text);
      expect(JSON.stringify(back, (_, v) => (v instanceof Uint8Array ? hex(v) : v))).toBe(JSON.stringify(link, (_, v) => (v instanceof Uint8Array ? hex(v) : v)));
    }
    expect(encodeLink({ request: 'pay', net, to: account, amount: '5' })).toBe(`ting:pay?to=${account}&amount=5&net=${net}`);
  });

  it('read the scheme, request and fields without regard to case, after an optional //', () => {
    const link = parseLink(`TING://Pay?TO=${account.toUpperCase()}&Net=26520BBE&extra=1`);
    expect(link).toEqual({ request: 'pay', net, to: account, coin: undefined, amount: undefined, note: undefined });
  });

  it('refuse what is not a link, or not one this version knows', () => {
    expect(() => parseLink(account)).toThrow(LinkError);
    expect(() => parseLink('ting:swap?net=26520bbe')).toThrow(/does not know/);
    expect(() => parseLink(`ting:pay?to=${account}`)).toThrow(/no net/);
    expect(() => parseLink(`ting:pay?to=${account}&net=xyz`)).toThrow(/8 hex digits/);
    expect(() => parseLink(`ting:pay?to=12&net=${net}`)).toThrow(/64 hex digits/);
    expect(() => parseLink(`ting:pay?to=${account}&amount=1,5&net=${net}`)).toThrow(/amount/);
    expect(() => parseLink(`ting:pay?to=${account}&to=${account}&net=${net}`)).toThrow(/twice/);
    expect(() => parseLink(`ting:pay?to=${account}&note=${'x'.repeat(81)}&net=${net}`)).toThrow(/longer/);
    expect(() => parseLink(`ting:create?input=a=b&net=${net}`)).toThrow(/input/);
    expect(() => parseLink(`ting:device?id=${account}&scheme=rsa&key=${hex(pub)}&net=${net}`)).toThrow(/scheme/);
    // 65 bytes, but not a point on the curve.
    expect(() => parseLink(`ting:device?id=${account}&scheme=secp256k1&key=04${'00'.repeat(64)}&net=${net}`)).toThrow(/public key/);
  });

  it('are taken only as the request asked for', () => {
    expect(() => parseLinkOf(encodeLink({ request: 'recover', net, id: account, keys: Uint8Array.of(1) }), 'device')).toThrow(/recover link, not a device link/);
  });

  it('give the account a pay link pays, or a bare account', () => {
    expect(accountIn(` ${account.toUpperCase()} `)).toBe(account);
    expect(accountIn(encodeLink({ request: 'pay', net, to: account }))).toBe(account);
    expect(accountIn(encodeLink({ request: 'recover', net, id: account, keys: Uint8Array.of(1) }))).toBeNull();
    expect(accountIn('hello')).toBeNull();
  });

  it('name a network by the first 8 hex digits of its chain ID', () => {
    expect(netOf(Uint8Array.of(0x26, 0x52, 0x0b, 0xbe, 0x40, 0x34))).toBe(net);
  });
});
