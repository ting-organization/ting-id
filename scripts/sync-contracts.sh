#!/usr/bin/env bash
# Copies the programs the app derives canonical ContractIds from (wiki:
# programs) into the app: the reference programs of the Ting repository (its
# `programs` folder), and policy-webauthn, Ting ID Web's passkey policy (that
# repository's `program.wasm`). Run after one of them changes, then run the
# core tests, which check the code hashes:
#
#   scripts/sync-contracts.sh [ting repository] [ting-id-web repository]   (defaults: ../ting ../ting-id-web)
set -euo pipefail
programs="${1:-../ting}/programs"
web="${2:-../ting-id-web}"
target="$(dirname "$0")/../core/src/main/resources/app/tingid/chain/contracts"
for name in policy-secp256k1 policy-p256 policy-multisig-secp256k1 id-registry coin batch; do
  cp "$programs/$name.wasm" "$target/$name.wasm"
done
cp "$web/program.wasm" "$target/policy-webauthn.wasm"
echo "copied 7 programs"
