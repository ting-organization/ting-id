# Ting ID

Ting ID is a wallet and identity app for Android, for the
[Ting](https://github.com/ting-organization/ting) network.

## Download

`Ting-ID.apk` is on the [releases page](https://github.com/ting-organization/ting-id/releases).

> **Early builds.** Ting ID is new software and still changing. It can have
> faults that cost you coins: keep in it only what you can afford to lose. It
> comes without warranty (see the licenses).

Open `Ting-ID.apk` on the phone and allow your browser to install apps. If Play
Protect offers to scan an app it does not know, let it, then choose Install
anyway. Every release is signed with the same certificate, whose SHA-256
fingerprint is in the release notes; an update installs over the app and keeps
its keys, while uninstalling the app deletes them.

Each release lists the files' SHA-256 in `SHA256SUMS`, and
`gh attestation verify Ting-ID.apk --repo ting-organization/ting-id` shows that
the file was built by this repository's release workflow.

## What it does

- A user is an identity: a spending account, a savings account whose payments
  wait seven days and can be cancelled meanwhile, devices that can act for it,
  a recovery phrase of twelve words, and guardians who can restore access and
  replace a phrase someone else got hold of. [RECOVERY.md](RECOVERY.md)
  describes the phrase.
- It sends, schedules and cancels payments in any coin of the standard coin
  program, and shows and scans payment codes (`ting:<account>`) as QR codes.
- With Settings → Coin tools on, a Coins tab lists every coin on the chain,
  creates coins, and mints coins whose minter is one of your accounts.
- Sites can ask it to sign in or to sign a statement; [SIGNING.md](SIGNING.md)
  describes how.

Keys are made on the phone; the written recovery phrase is their only copy
outside it. The app reads the chain through
one or more nodes and checks every record against the block it comes from;
with two or more nodes, each checks the others.

## Build and run

You need Node.js 22; native builds also need the Android SDK and JDK 17. The
app is built with Expo (React Native), which also runs it in a browser for
development.

```bash
npm install
npm run web                 # in the browser, at http://localhost:8081
npm start                   # the Expo dev server, for a development build on a phone
npx expo run:android        # a native build on a connected phone or an emulator
```

A build of your own starts with the node at `http://127.0.0.1:18441`, which
[`ting init`](https://github.com/ting-organization/ting#readme) sets up; add
others in Settings → Nodes. Plain http works only for the device itself and the
home network. The browser build needs the node to allow it: `ting api enable --cors`.

## Test

```bash
npm test                    # vitest
npm run typecheck
npm run lint
```

`tests/vectors.json` holds byte vectors from a Rust implementation of the same
formats; the tests check that the app produces exactly those bytes.
`tests/proof-fixture.json` is a storage answer with its proofs, taken from a
node, for the proof checks.

Other scripts:

- `npm run sync-contracts -- <path of the ting repository>` embeds the
  reference programs (`src/chain/contracts.generated.ts`), from which the app
  derives their ContractIds on any chain. Run it when the programs change.
- `npm run icons` renders the app icon, the Android icon layers, the favicon and
  the splash from the logo in `assets/logo/`.

| Path | Contents |
|---|---|
| `src/chain/` | Byte formats, hashing, keys, accounts, coin, identity, fees, transactions, proofs, the node client and the node pool |
| `src/state/` | Saved state, the keystore, the cache of node data, node queries and submissions |
| `src/config/` | Known coins per chain |
| `src/ui/` | Theme, components and texts |
| `src/app/` | Screens (Expo Router) |
| `tests/` | Vitest suite |

## Releases

A version tag (`v1.2.3`) runs the [release workflow](.github/workflows/release.yml).
It builds the app at that version, signs it with the release key, which needs
approval, and creates a draft release to look over and publish. The repository
variable `TING_NODES` (comma-separated https addresses) names the nodes a new
installation starts with.

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT), at your option. The font and icons the app ships
with are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Security issues: see [SECURITY.md](SECURITY.md).
