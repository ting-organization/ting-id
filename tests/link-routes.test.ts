// Where a Ting link leads: every link from inside the app, only pay, create
// and sign from outside it.
import { describe, expect, it } from 'vitest';
import { encodeLink } from '../src/chain/links';
import { routeOf, systemPath } from '../src/ui/linkRoutes';

const net = '15d6549c';
const id = 'cd'.repeat(32);
const links = {
  pay: encodeLink({ request: 'pay', net, to: id }),
  create: encodeLink({ request: 'create', net, input: Uint8Array.of(1, 2, 3) }),
  sign: 'ting:sign?url=https%3A%2F%2Fforum.example%2Fr%2F1',
  device: `ting:device?id=${id}&scheme=secp256k1&key=0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8&net=${net}`,
  recover: encodeLink({ request: 'recover', net, id, keys: Uint8Array.of(1) }),
};

describe('a link opened in the app', () => {
  it('opens its own screen, with the link', () => {
    expect(routeOf(links.pay, 'inside')).toEqual({ pathname: '/send', params: { code: links.pay } });
    expect(routeOf(links.create, 'inside')).toEqual({ pathname: '/sponsor', params: { code: links.create } });
    expect(routeOf(links.sign, 'inside')).toEqual({ pathname: '/sign', params: { code: links.sign } });
    expect(routeOf(links.device, 'inside')).toEqual({ pathname: '/identity/propose', params: { kind: 'addKey', code: links.device } });
    expect(routeOf(links.recover, 'inside')).toEqual({ pathname: '/identity/recovery', params: { mode: 'help', code: links.recover } });
  });

  it('says what is wrong with anything else', () => {
    expect(routeOf('hello', 'inside')).toBeInstanceOf(Error);
    expect(routeOf('ting:swap?net=15d6549c', 'inside')).toBeInstanceOf(Error);
  });
});

describe('a link opened from outside', () => {
  it('opens pay, create and sign', () => {
    expect(systemPath(links.pay, 7)).toBe(`/send?code=${encodeURIComponent(links.pay)}&at=7`);
    expect(systemPath(links.create).startsWith('/sponsor?code=')).toBe(true);
    expect(systemPath(links.sign).startsWith('/sign?code=')).toBe(true);
  });

  it('opens nothing for a device or recovery link, nor for anything else', () => {
    expect(systemPath(links.device)).toBe('/');
    expect(systemPath(links.recover)).toBe('/');
    expect(systemPath('https://example.com')).toBe('/');
  });
});
