// Off-chain signing (SIGNING.md): the document's exact bytes, what the app
// refuses to show, the request and answer checks, and the site's verification
// of an answer against the chain's records.
import { describe, expect, it } from 'vitest';
import { policyAccount, signMessage } from '../src/chain/accounts';
import { Writer, hex, utf8 } from '../src/chain/bytes';
import { canonical } from '../src/chain/contracts';
import { sha256 } from '../src/chain/hash';
import * as idp from '../src/chain/identity';
import { publicKey } from '../src/chain/keys';
import {
  Envelope,
  OffchainError,
  SignDocument,
  answerJson,
  bodyBlocks,
  decodeDocument,
  documentHash,
  encodeDocument,
  isAttachment,
  matchCode,
  originOf,
  parseSignCode,
  readAnswer,
  readRequest,
  requestJson,
  signCode,
  signedMessage,
  verifyAnswer,
} from '../src/chain/offchain';

const fill = (b: number, n = 32) => new Uint8Array(n).fill(b);
const CHAIN = Uint8Array.from(Buffer.from('26520bbe40340aabafdc0da89c128e4849146d4b3e59edabd7899d6782a6e653', 'hex'));
const browserKey = Uint8Array.of(4, ...fill(0x22, 64));

const signIn: SignDocument = {
  request: { kind: 'signIn', sessionUntil: 1_800_000_000n, key: { scheme: 'p256', publicKey: browserKey } },
  title: 'Sign in to the Ting forum',
  body: 'Welcome back.\n\n- read\n- **post**',
  fields: [
    { label: 'Browser', value: 'Firefox on Windows' },
    { label: 'Tip jar', value: '100 DLR' },
  ],
};

describe('the document', () => {
  it('has exactly the bytes the convention describes', () => {
    const expected = new Writer()
      .u8(1)
      .u8(1)
      .u64(1_800_000_000n)
      .u8(1)
      .bytes(browserKey)
      .bytes(utf8('Sign in to the Ting forum'))
      .bytes(utf8('Welcome back.\n\n- read\n- **post**'))
      .u32(2)
      .bytes(utf8('Browser'))
      .bytes(utf8('Firefox on Windows'))
      .bytes(utf8('Tip jar'))
      .bytes(utf8('100 DLR'))
      .finish();
    const bytes = encodeDocument(signIn);
    expect(hex(bytes)).toBe(hex(expected));
    expect(decodeDocument(bytes)).toEqual(signIn);
  });

  it('round-trips grants and statements', () => {
    const grant: SignDocument = {
      request: { kind: 'grant', until: 1_900_000_000n, scopes: ['post', 'react:thread/1'], key: null },
      title: 'Let the forum post for you',
      body: '',
      fields: [
        { label: 'Account', value: '0101…0101' },
        { label: 'Starts', value: '1 Sep 2026' },
      ],
    };
    const statement: SignDocument = {
      request: { kind: 'statement', attachment: { hash: fill(5), mediaType: 'application/pdf', name: 'Agreement.pdf' } },
      title: 'I agree to the terms',
      body: 'Æ ø å, 日本語 and 👍 are fine.',
      fields: [],
    };
    for (const doc of [grant, statement]) expect(decodeDocument(encodeDocument(doc))).toEqual(doc);
  });

  it('refuses anything it cannot show exactly', () => {
    const good = encodeDocument(signIn);
    const refuse = (bytes: Uint8Array) => expect(() => decodeDocument(bytes)).toThrow(OffchainError);
    refuse(Uint8Array.of(...good, 0)); // a trailing byte
    refuse(Uint8Array.of(2, ...good.subarray(1))); // another version
    refuse(Uint8Array.of(1, 9, ...good.subarray(2))); // another kind
    refuse(good.subarray(0, good.length - 3)); // cut short
    const withTitle = (title: string) => {
      const w = new Writer().u8(1).u8(3).u8(0).bytes(utf8(title)).bytes(new Uint8Array()).u32(0);
      return w.finish();
    };
    expect(decodeDocument(withTitle('Plain title')).title).toBe('Plain title');
    refuse(withTitle('Pay ‮evil')); // a direction override
    refuse(withTitle('in​visible')); // a zero-width space
    refuse(withTitle('two\nlines')); // a title is one line
    refuse(withTitle(' padded')); // no surrounding spaces
    refuse(withTitle('é')); // not normalized: é as e + accent
    refuse(new Writer().u8(1).u8(3).u8(0).bytes(Uint8Array.of(0xc3)).bytes(new Uint8Array()).u32(0).finish()); // invalid UTF-8
    refuse(new Writer().u8(1).u8(3).u8(0).bytes(Uint8Array.of(0xc0, 0x80)).bytes(new Uint8Array()).u32(0).finish()); // an overlong encoding
    // A field is a label and a value, each one clean line of text, shown as written.
    const detail = (value: string) => new Writer().u8(1).u8(3).u8(0).bytes(utf8('T')).bytes(new Uint8Array()).u32(1).bytes(utf8('L')).bytes(utf8(value)).finish();
    expect(decodeDocument(detail('150 DLR')).fields[0]).toEqual({ label: 'L', value: '150 DLR' });
    refuse(detail(''));
    refuse(detail('two\nlines'));
    refuse(detail('Pay ‮evil'));
    expect(() => encodeDocument({ ...signIn, request: { kind: 'grant', until: 0n, scopes: ['Post Things'], key: null } })).toThrow(OffchainError);
  });
});

describe('codes and requests', () => {
  const url = 'https://forum.ting.example/api/ting/requests/abc';
  const envelope: Envelope = { chainId: CHAIN, origin: 'https://forum.ting.example', nonce: fill(3, 32), issuedAt: 1000n, expiresAt: 1300n, document: encodeDocument(signIn) };
  const json = requestJson({ ...envelope, signer: null, callback: 'https://forum.ting.example/api/ting/requests/abc/answer' });

  it('accepts secure sites and this device only', () => {
    expect(originOf('https://Forum.Ting.Example:8443/x?y')).toBe('https://forum.ting.example:8443');
    expect(originOf('http://localhost:3000/a')).toBe('http://localhost:3000');
    expect(originOf('http://forum.ting.example/a')).toBeNull();
    expect(originOf('https://user:pw@forum.ting.example/')).toBeNull();
    // Plain ASCII only: a look-alike name in other letters is refused, its xn-- form is shown as it is.
    expect(originOf('https://bаnk.example/req')).toBeNull();
    expect(originOf('https://bank.example/réq')).toBeNull();
    expect(originOf('https://xn--bnk-uqa.example/req')).toBe('https://xn--bnk-uqa.example');
    expect(originOf('javascript:alert(1)')).toBeNull();
    expect(parseSignCode(signCode(url))).toBe(url);
    expect(parseSignCode('ting:sign?url=http%3A%2F%2Fevil.example%2F')).toBeNull();
    expect(parseSignCode(`ting:${'5a'.repeat(32)}`)).toBeNull();
  });

  it('takes the origin from where the request was fetched, and the answer must go back there', () => {
    const r = readRequest(json, url, 1100);
    expect(r.origin).toBe('https://forum.ting.example');
    expect(r.request.title).toBe('Sign in to the Ting forum');
    expect(() => readRequest({ ...json, callback: 'https://evil.example/answer' }, url, 1100)).toThrow(/another site/);
    expect(readRequest({ ...json, return: 'https://forum.ting.example/t/1' }, url, 1100).returnTo).toBe('https://forum.ting.example/t/1');
    expect(() => readRequest({ ...json, return: 'https://evil.example/' }, url, 1100)).toThrow(/back to another site/);
    expect(() => readRequest(json, 'https://evil.example/relay', 1100)).toThrow(/another site/);
    expect(() => readRequest(json, url, 1300)).toThrow(/expired/);
    expect(() => readRequest({ ...json, issued_at: 2000, expires_at: 2100 }, url, 1100)).toThrow(/clock/);
    expect(() => readRequest({ ...json, expires_at: 1000 + 86_401 }, url, 1100)).toThrow(/clock/);
    expect(() => readRequest({ ...json, nonce: '00' }, url, 1100)).toThrow(/nonce/);
  });

  it('fetches the file a statement covers from the same site, and only that file', () => {
    const pdf = utf8('%PDF-1.7 the agreement');
    const covering = encodeDocument({
      request: { kind: 'statement', attachment: { hash: sha256(pdf), mediaType: 'application/pdf', name: 'Agreement.pdf' } },
      title: 'I agree',
      body: '',
      fields: [],
    });
    const statement = requestJson({ ...envelope, document: covering, signer: null, callback: json.callback, file: 'https://forum.ting.example/files/agreement.pdf' });
    expect(readRequest(statement, url, 1100).file).toBe('https://forum.ting.example/files/agreement.pdf');
    expect(() => readRequest({ ...statement, file: undefined }, url, 1100)).toThrow(/does not offer/);
    expect(() => readRequest({ ...statement, file: 'https://evil.example/agreement.pdf' }, url, 1100)).toThrow(/does not offer/);
    expect(() => readRequest({ ...json, file: 'https://forum.ting.example/x.pdf' }, url, 1100)).toThrow(/does not cover/);
    const attachment = { hash: sha256(pdf), mediaType: 'application/pdf', name: 'Agreement.pdf' };
    expect(isAttachment(pdf, attachment)).toBe(true);
    expect(isAttachment(utf8('%PDF-1.7 another agreement'), attachment)).toBe(false);
  });

  it('binds everything that matters in the signed message', () => {
    const account = fill(1);
    const base = hex(signedMessage(envelope, account));
    expect(base.startsWith(hex(new Writer().bytes(utf8('app/offchain-v1')).finish()))).toBe(true);
    expect(base.endsWith(hex(documentHash(envelope.document)))).toBe(true);
    for (const changed of [
      { ...envelope, origin: 'https://evil.example' },
      { ...envelope, nonce: fill(4, 32) },
      { ...envelope, chainId: fill(0xaa) },
      { ...envelope, expiresAt: 1301n },
      { ...envelope, document: encodeDocument({ ...signIn, title: 'Sign in to the Ting forum!' }) },
    ])
      expect(hex(signedMessage(changed, account))).not.toBe(base);
    expect(hex(signedMessage(envelope, fill(2)))).not.toBe(base);
    expect(matchCode(envelope.nonce)).toMatch(/^\d\d$/);
  });
});

describe('checking an answer', () => {
  const c = canonical(CHAIN);
  const id = fill(7);
  const secret = fill(0x11);
  const pub = publicKey('secp256k1', secret);
  const core: idp.Core = {
    opNonce: 0n,
    changeEpoch: 0n,
    recoveryEpoch: 0n,
    keyEpoch: 2n,
    nextKeyId: 3n,
    keyCount: 1n,
    guardianCount: 0n,
    slotCount: 2n,
    threshold: 0n,
    changeDelay: 604_800n,
    recoveryDelay: 172_800n,
    nextChange: 0n,
  };
  const records = new Map<string, Uint8Array>([
    [hex(c.registry) + hex(idp.coreKey(id)), idp.encodeCore(core)],
    [hex(c.registry) + hex(idp.keyKey(id, 2, 1)), idp.encodeKeyRecord({ policy: c.policySecp256k1, params: pub })],
  ]);
  const node = { storage: async (contract: Uint8Array, key: Uint8Array) => records.get(hex(contract) + hex(key)) ?? null };
  const request: Envelope = { chainId: CHAIN, origin: 'https://forum.ting.example', nonce: fill(3, 32), issuedAt: 1000n, expiresAt: 1300n, document: encodeDocument(signIn) };
  const answer = (env: Envelope, slot = 0, keyId = 1) => {
    const params = idp.params(id, slot);
    const account = idp.account(c.registry, id, slot);
    const witness = idp.keyAuth(keyId, signMessage('secp256k1', secret, signedMessage(env, account)));
    return { nonce: env.nonce, account, policy: c.registry, params, witness };
  };

  it('accepts an ID signing with a current key, through the JSON both sides send', async () => {
    const received = readAnswer(JSON.parse(JSON.stringify(answerJson(answer(request)))));
    if ('declined' in received) throw new Error('declined');
    const signed = await verifyAnswer(node, request, received, 1100);
    expect([hex(signed.id!), signed.slot, signed.keyId, signed.scheme]).toEqual([hex(id), 0, 1n, 'secp256k1']);
  });

  it('refuses a signature made for anything else', async () => {
    const refuse = (p: Promise<unknown>, why: RegExp) => expect(p).rejects.toThrow(why);
    // Signed for another site's request, or for another document, does not match this one.
    await refuse(verifyAnswer(node, request, answer({ ...request, origin: 'https://evil.example' }), 1100), /signature/);
    await refuse(verifyAnswer(node, request, answer({ ...request, document: encodeDocument({ ...signIn, title: 'Other' }) }), 1100), /signature/);
    await refuse(verifyAnswer(node, request, answer(request), 1301), /expired/);
    await refuse(verifyAnswer(node, request, answer(request, 2), 1100), /no such account/);
    // A key of another epoch, removed or replaced by a recovery, has no record now.
    await refuse(verifyAnswer(node, request, answer(request, 0, 2), 1100), /current key/);
    const wrongAccount = { ...answer(request), account: fill(1) };
    await refuse(verifyAnswer(node, request, wrongAccount, 1100), /match its policy/);
    await refuse(verifyAnswer(node, request, { ...answer(request), nonce: fill(4, 32) }, 1100), /another request/);
  });

  it('accepts a plain P-256 account too', async () => {
    const p256 = fill(0x33);
    const key = publicKey('p256', p256);
    const account = policyAccount(c.policyP256, key);
    const witness = signMessage('p256', p256, signedMessage(request, account));
    const signed = await verifyAnswer(node, request, { nonce: request.nonce, account, policy: c.policyP256, params: key, witness }, 1100);
    expect([signed.id, signed.scheme]).toEqual([null, 'p256']);
  });
});

describe('the body', () => {
  it('lays out paragraphs, lists, bold and italic, and leaves the rest as written', () => {
    expect(bodyBlocks('Hello **you**,\nsecond line\n\n- one\n- *two*\n\n3. three\n4. four\n\nA * lone star and **open')).toEqual([
      { type: 'paragraph', lines: [[{ text: 'Hello ' }, { text: 'you', bold: true }, { text: ',' }], [{ text: 'second line' }]] },
      { type: 'list', items: [{ marker: '•', spans: [{ text: 'one' }] }, { marker: '•', spans: [{ text: 'two', italic: true }] }] },
      { type: 'list', items: [{ marker: '3.', spans: [{ text: 'three' }] }, { marker: '4.', spans: [{ text: 'four' }] }] },
      { type: 'paragraph', lines: [[{ text: 'A * lone star and **open' }]] },
    ]);
    expect(bodyBlocks('')).toEqual([]);
  });
});
