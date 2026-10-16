# Ting ID

Ting ID is a wallet and identity app for the [Ting](https://github.com/ting-organization/ting)
network, for Android, Windows, macOS and Linux.

## Downloads

Ting ID for Windows, macOS, Linux and Android is on the
[releases page](https://github.com/ting-organization/ting-id/releases).

> **Early builds.** Ting ID is new software and still changing. It can have
> faults that cost you coins: keep in it only what you can afford to lose. It
> comes without warranty (see the licenses).

The desktop apps are not code-signed yet, so the first start takes a step more:

| | First start |
|---|---|
| Windows | At "Windows protected your PC", choose More info, then Run anyway. |
| macOS | Open the `.dmg` and drag Ting ID to Applications. When macOS says it cannot check the app, open System Settings → Privacy & Security and choose Open Anyway. After an update, macOS may ask again whether Ting ID may use its keychain items: choose Always Allow. |
| Linux | Make the AppImage executable (`chmod +x Ting-ID-x86_64.AppImage`), or install the `.deb`. |
| Android | Open `Ting-ID.apk` on the phone and allow your browser to install apps. Every release is signed with the same certificate; its fingerprint is in the release notes. Updates keep the app's keys; uninstalling the app deletes them. |

Each release lists every file's SHA-256 in `SHA256SUMS`, and
`gh attestation verify <file> --repo ting-organization/ting-id` shows that a
file was built by this repository's release workflow.

## What it does

- A user is an identity: a spending account, a savings account whose payments
  wait seven days and can be cancelled meanwhile, devices that can act for it,
  and guardians who can restore access when every device is lost.
- It sends, schedules and cancels payments in any coin of the standard coin
  program, and shows and scans payment codes (`ting:<account>`) as QR codes.
- It lists every coin on the chain, creates coins, and mints coins whose
  minter is one of your accounts: on the desktop always, on the phone with the
  coin tools switched on in Settings.
- Sites can ask it to sign in or to sign a statement; [SIGNING.md](SIGNING.md)
  describes how.

Keys are made on the device and never leave it. The apps read the chain
through one or more nodes and check every record against the block it comes
from; with two or more nodes, each checks the others.

## The code

| Folder | App | Built with |
|---|---|---|
| [`mobile/`](mobile) | Android and iOS, and the browser for development | Expo (React Native) |
| [`desktop/`](desktop) | Windows, macOS, Linux | Tauri 2 (Rust) with React |

The phone app owns the shared logic (`mobile/src/chain`, `mobile/src/state`,
`mobile/src/config`) and the logo. The desktop app copies them with
`npm run sync-core` into `desktop/src/core`; change them in `mobile/`, then sync.

## Building

You need Node.js 22. The desktop app also needs Rust (the version in
`desktop/src-tauri/rust-toolchain.toml`) and Tauri's
[system dependencies](https://tauri.app/start/prerequisites/). Each folder's
README says how to run and test that app.

The apps talk to Ting nodes. A release starts with the network's public nodes;
a build of your own starts with a node on the same computer, which
[`ting init`](https://github.com/ting-organization/ting#readme) sets up, and
Settings → Nodes takes any other.

## Releases

A version tag (`v1.2.3`) runs the [release workflow](.github/workflows/release.yml).
It builds every app at that version, signs the Android app with the release key,
which needs approval, and creates a draft release to look over and publish. The
repository variable `TING_NODES` (comma-separated https addresses) names the
nodes a new installation starts with.

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT), at your option. The fonts and icons the apps ship
with are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Security issues: see [SECURITY.md](SECURITY.md).
