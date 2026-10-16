# Ting ID for phones

The phone app of [Ting ID](../README.md), built with Expo: one code base for
Android, iOS and, for development, the browser. It also holds the logic the
desktop app shares. Settings → Coin tools adds a Coins tab that lists every
coin on the chain, creates coins and mints them.

## Run

```bash
npm install
npm run web                 # in the browser, at http://localhost:8081
npm start                   # the Expo dev server, for Expo Go or a development build on a phone
npx expo run:android        # a native build on a connected phone or an emulator
```

A build of your own starts with the node at `http://127.0.0.1:18441`; add others
in Settings → Nodes. Plain http works only for this computer and the home
network: a phone reaches a node on your computer by the computer's address,
such as `http://192.168.1.10:18441`, with the node's REST API bound to it. The
browser build needs the node to allow it: `ting api enable --cors`.

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

## Other scripts

- `npm run sync-contracts -- <path of the ting repository>` embeds the
  reference programs (`src/chain/contracts.generated.ts`), from which the app
  derives their ContractIds on any chain. Run it when the programs change.
- `npm run icons` renders the app icon, the Android icon layers, the favicon and
  the splash from the logo in `assets/logo/`.

## Layout

| Path | Contents |
|---|---|
| `src/chain/` | Byte formats, hashing, keys, accounts, coin, identity, fees, transactions, proofs, the node client and the node pool |
| `src/state/` | Saved state, the keystore, the cache of node data, node queries and submissions |
| `src/config/` | Known coins per chain |
| `src/ui/` | Theme, components and the texts shared with the desktop app |
| `src/app/` | Screens (Expo Router) |
| `tests/` | Vitest suite |
