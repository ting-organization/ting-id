# Ting links

A Ting link is what one person asks of another: pay me, set up my identity,
add this device, approve my recovery, or sign in on a site. The same text works
three ways: as a QR code to scan, as a link to copy into a message and paste in
Ting ID, and tapped on the phone, which opens Ting ID for the `ting:` scheme.

```text
ting:<request>?<field>=<value>&…
```

## Requests

| Request | Fields | Asks |
|---|---|---|
| `pay` | `to`, `coin`?, `amount`?, `note`?, `net` | Pay the account `to`, in `coin`, `amount` if given |
| `create` | `input`, `name`?, `coin`?, `amount`?, `net` | Create the identity whose creation input is `input`, and send it coins to start with |
| `device` | `id`, `scheme`, `key`, `net` | Add the public key `key` of `scheme` to the identity `id` |
| `recover` | `id`, `keys`, `net` | Approve the recovery of identity `id` to the key list `keys` |
| `sign` | `url` | A site's signing request, fetched from `url` ([TOCS-1](TOCS-1.md)) |

## Values

- **32-byte IDs and accounts** (`to`, `coin`, `id`): 64 lowercase hex digits.
- **Longer bytes** (`input`, `keys`): base64url without padding (RFC 4648 §5),
  with no bits left over.
- **`key`**: the 65-byte uncompressed public key in hex; `scheme` is
  `secp256k1` or `p256`.
- **`amount`**: decimal, in the coin's units, with a dot: `12.5`.
- **`name`, `note`**: text of at most 80 characters, percent-encoded.
- **`net`**: the first 8 hex digits of the chain ID. A link for another network
  is refused.

`input` is the identity registry's creation input, after the op byte
([the registry](https://github.com/ting-organization/ting/blob/master/node/wiki/programs/identity.md)),
and `keys` its key list. Both are checked on their own: the ID is the hash of
the creation input, so a creation can only make the identity it describes.

## Reading

- The scheme, the request and field names are read without regard to case,
  and a `//` after the scheme is tolerated.
- Fields may come in any order. A field given twice makes the link invalid,
  and unknown fields are ignored.
- A request the app does not know is shown as such, not guessed at.
- Nothing happens until the person confirms on the screen the link opens.

## Opening from outside the app

A link tapped in another app opens Ting ID only for `pay`, `create` and
`sign`: their screens move nothing but the person's own coins, and only once
they confirm. `device` and `recover` links work only when scanned or pasted in
Ting ID, so a link in a message cannot lead someone into adding a stranger's
key to their identity.

## Setting up someone else's identity

The newcomer's phone builds the identity: its keys, from the recovery phrase,
and its guardians and delays. It submits the creation itself, which costs
nothing while blocks have room, and shows a `create` link. Whoever opens the
link sees the ID and the configuration and chooses coins to send. If no block
has the identity yet, their phone creates it, paying the fee, and sends the
coins to its Spending account. The ID and its accounts are known before the
identity exists, so a payment to it is valid either way.
