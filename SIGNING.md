# Signing for sites

A site can ask a Ting ID user to sign something off chain: to sign in, to
grant a permission, or to sign a statement. The site shows a code, the app
fetches the request, shows what it asks in the app's own words, and on
approval sends the signed answer back to the site. Nothing of this touches the
chain; the site checks the answer against the chain's current records.

The code is `src/chain/offchain.ts`, which sites may copy, and
`tests/offchain.test.ts` pins the formats.

## The flow

1. The site stores a request and shows its code, as a QR code to scan with a
   phone, or as a link that opens the app on the same device:
   `ting:sign?url=<percent-encoded address of the request>`, one of the
   [Ting links](LINKS.md). The app registers the `ting:` scheme, so no domain
   of the app is involved.
2. The app fetches the address. The request's **origin** is the origin of that
   address, `https://host[:port]`; plain `http` is accepted only for
   `localhost`, `127.0.0.1` and `[::1]`, for sites in development.
3. The app checks the request, shows it and lets the user approve or decline.
4. The app POSTs the answer, as JSON, to the request's `callback`, which must
   have the same origin. The site answers with JSON `{ "message": "…" }`, which
   the app shows; a status other than 2xx is a failure, and its `message` says
   why.
5. On a phone, when the request has a `return` address, the app opens it after
   signing, which brings the user back to the site's page in the browser.
   The page that showed the code learns the outcome from its own server, which
   received the answer.

The app fetches without a browser's cross-origin rules; its browser build,
for development, does not, so a site that wants to work with it too serves the
request and the callback with `Access-Control-Allow-Origin: *`.

## The request

```json
{
  "type": "ting-sign-request",
  "version": 1,
  "chain": "<ChainId, 64 hex digits>",
  "nonce": "<16 to 64 bytes in hex, used once>",
  "issued_at": 1792000000,
  "expires_at": 1792000300,
  "signer": "<ID, 64 hex digits; optional>",
  "document": "<the document's bytes in hex>",
  "callback": "https://forum.example/api/ting/requests/abc/answer",
  "return": "https://forum.example/sign-in",
  "file": "https://forum.example/files/agreement.pdf"
}
```

The app refuses a request that has expired, that was issued more than five
minutes ahead of its clock, that lives longer than a day, or whose callback is
on another origin, or a `return` address on another origin. `signer` names
the ID the site expects, for a returning user; without it the user chooses
which of their identities signs. `return` is optional.

`file` is where the app fetches the file a statement covers, on the same
origin. It is there exactly when the statement has an attachment; the app
refuses a request with a statement that covers a file and no `file`, a `file`
on another origin, or a `file` without such a statement. The site serves the
file's bytes; the app fetches them when the user opens the file, keeps them
only if their SHA-256 is the attachment's hash, shows them, and lets the user
sign only after opening the file.

## The document

What the user reads. Integers are little-endian, `Bytes(x) = u32(len(x)) ‖ x`,
and text is UTF-8.

```text
document = u8 version (1) ‖ u8 kind ‖ kind data
         ‖ Bytes(title) ‖ Bytes(body) ‖ u32 n ‖ n × field
field    = Bytes(label) ‖ Bytes(value)
key      = u8 0                                    no key
         | u8 1 ‖ Bytes(public key)                an uncompressed P-256 point, 65 bytes
```

| Kind | Kind data | The app says |
|---|---|---|
| 1 sign in | `u64 session_until ‖ key` | Sign in to *origin* (on this browser, when a key is given) until *session_until* |
| 2 grant | `u64 until ‖ u32 n ‖ n × Bytes(scope) ‖ key` | Allow *origin* to *scopes* as *user* until *until* |
| 3 statement | `u8 0`, or `u8 1 ‖ hash ‖ Bytes(media type) ‖ Bytes(name)` | Sign this statement for *origin*, covering the attached file |

A scope is 1 to 64 characters of lowercase letters, digits and `: . _ / -`,
starting with a letter or digit. An attachment's hash is the SHA-256 of the
file's bytes, and its media type is `type/subtype` in lowercase.

A field is a label and a value the site chooses, each one line of text. The
app shows both exactly as written and gives neither any meaning of its own:
what a value says is the site's claim.

The body is paragraphs separated by blank lines, `**bold**`, `*italic*`, and
lists of lines starting `- ` or `1. `; anything else shows as written, and
nothing is loaded from the network.

The app refuses the whole document, never skipping a part, when it finds:

- an unknown version, kind or key scheme, or trailing bytes;
- text that is not valid shortest-form UTF-8 or not in NFC;
- control characters (a newline is allowed in the body only), direction
  overrides and isolates, zero-width and other invisible characters,
  surrogates or noncharacters;
- a title, label, value or attachment name that is empty, spans lines or has
  spaces around it;
- more than 64 KiB.

## The signed message

The app builds the message itself; a site never supplies bytes to sign.

```text
message = Bytes("app/offchain-v1") ‖ ChainId ‖ account ‖ Bytes(origin) ‖ Bytes(nonce)
        ‖ u64 issued_at ‖ u64 expires_at ‖ H("app/offchain-v1/document", document)
```

It is signed like every Ting application message, over
`H("app/sign-v1", message)` with the policies' witness format. The tag keeps it
apart from every on-chain message, which starts with its own application's
tag, so an answer can never authorize anything on chain. The origin comes from
where the app fetched the request, so a signature for one site is worthless to
another. For an identity the account is its slot 0, the spending account.

For a sign-in, the app and the site both show two digits,
`(h[0] · 256 + h[1]) mod 100` of `h = H("app/offchain-v1/match", nonce)`, so
the user can see that the request is the one on their screen.

## The answer

```json
{ "type": "ting-sign-answer", "version": 1, "nonce": "<hex>",
  "account": "<hex>", "policy": "<hex>", "params": "<hex>", "witness": "<hex>" }
```

or, when the user declines,

```json
{ "type": "ting-sign-answer", "version": 1, "nonce": "<hex>", "declined": true }
```

`policy`, `params` and `witness` are what the account's policy checks on chain:
for an identity, the registry, `id ‖ u64 slot` and `u64 key_id ‖ Bytes(signature)`.

## Checking an answer

`verifyAnswer` does this with the site's stored request, never the request
data an answer could bring:

1. The nonce is the stored request's, the request has not expired, and the
   site marks the nonce used.
2. `account = H("app/account-v1/policy", policy ‖ Bytes(params))`.
3. The message is rebuilt from the stored request and `account`.
4. For the canonical registry: the ID's core record exists and has the slot;
   the witness names a key whose record exists in the ID's current key epoch,
   so a removed or recovered-away key fails; that key is a canonical
   secp256k1 or P-256 key, and its signature over the digest is valid.
5. For the canonical secp256k1 or P-256 policy: the signature is valid for the
   key in `params`.

Other policies, such as multisig or IDs nested in IDs, are refused for now.
The records are read through the node's
`/v1/contracts/{id}/storage/{key}` endpoint, so a site needs a node it trusts
and a secure connection to it.
