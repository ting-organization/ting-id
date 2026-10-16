# Ting ID for the desktop

The desktop app of [Ting ID](../README.md) for Windows, macOS and Linux: a
[Tauri](https://tauri.app) window with React inside and a Rust side. Its Coins
page lists every coin on the chain, creates coins, and mints coins whose minter
is one of your accounts; the phone app has the same behind a switch.

## Run

You need Node.js 22, Rust (the version in `src-tauri/rust-toolchain.toml`) and
Tauri's [system dependencies](https://tauri.app/start/prerequisites/).

```bash
npm install
npm run tauri dev           # the window, with the UI served by Vite
npm run tauri build         # installers, in src-tauri/target/release/bundle
```

On Windows with several Visual Studio installations, the newest MSVC toolset
may be incomplete; `pwsh scripts/cargo-env.ps1 <command>` runs a command with a
complete toolset and SDK.

A build of your own starts with the node at `http://127.0.0.1:18441`; add others
in Settings → Nodes. Plain http works only for this computer and the home
network.

## Shared code

`src/core/` and `tests/` are copies of the phone app's code (`../mobile`),
made by `npm run sync-core`. Change them there, then sync.

## Test

```bash
npm test                    # vitest
npm run typecheck
cargo test --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml -- --ignored   # writes to and removes from the OS keychain
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets
```

`npm run icons` renders the window and installer icons from `public/logo.svg`.

## Layout

| Path | Contents |
|---|---|
| `src/core/` | Shared logic from the phone app (generated) |
| `src/platform/` | The Tauri bridge, and what the shared code expects from Expo |
| `src/ui/` | Components, icons, layout |
| `src/pages/` | Pages, and the dialogs that open over them |
| `src-tauri/` | Rust side: key secrets in the OS keychain, the saved state in the app data folder, the HTTP plugin for nodes and sites; Tauri configuration, permissions and icons |
| `tests/` | Vitest suite |
