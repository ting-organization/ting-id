#!/usr/bin/env bash
# Copies the reference programs of the Ting repository (its `programs`
# folder) into the app, which derives their canonical ContractIds on any chain
# from them (wiki: programs). Run after the programs change, then run the core
# tests, which check the code hashes:
#
#   scripts/sync-contracts.sh [path of the ting repository]   (default: ../ting)
set -euo pipefail
programs="${1:-../ting}/programs"
target="$(dirname "$0")/../core/src/main/resources/app/tingid/chain/contracts"
for name in policy-secp256k1 policy-p256 policy-multisig-secp256k1 id-registry coin batch; do
  cp "$programs/$name.wasm" "$target/$name.wasm"
done
echo "copied 6 programs"
