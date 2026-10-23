#!/usr/bin/env bash
# Sets up a development chain the app can use: a node made with
# `ting init --dev`, the reference programs deployed canonically, so that the
# app finds the registry and the policies at the ContractIds it derives from
# them, and Daler deployed with the same code and configuration as on the
# production network, which gives the ContractId the app lists for development
# chains (KnownCoins.kt).
#
#   scripts/dev-chain.sh <ting binary> <data directory> [first port]
#
# The node listens on 127.0.0.1 only: control on the first port (default
# 18740), the API on the next and peers on the one after, apart from a
# production node's 18440-18442. Run it again to start the node; programs
# already there are left alone. Needs curl and python3.
set -euo pipefail
ting="$1"
dir="$2"
port="${3:-18740}"
api="127.0.0.1:$((port + 1))"
here="$(cd "$(dirname "$0")" && pwd)"
contracts="$here/../core/src/main/resources/app/tingid/chain/contracts"
daler=851dc8161155b1cfdab1554b587be613741b89e22b48232338fc36d0ad856140

if [ ! -e "$dir/config.json" ]; then
  "$ting" init --data-dir "$dir" --dev --api --name "Ting ID development" \
    --control "127.0.0.1:$port" --api-bind "$api" --p2p "127.0.0.1:$((port + 2))" >/dev/null
  # Read by ting-miner's node (github.com/ting-organization/ting-miner), which
  # then claims Daler's block reward in every block it builds; the reference
  # node leaves it alone.
  cat > "$dir/miner.json" <<EOF
{ "assets": { "$daler": 1.0 }, "claims": ["$daler"], "unpaid": true }
EOF
fi
"$ting" status --data-dir "$dir" >/dev/null 2>&1 || "$ting" start --data-dir "$dir" >/dev/null
chain="$(curl -fsS "http://$api/v1/status" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["chain_id"])')"

# Each deployment's envelope: version 1, kind 1 (deploy), the canonical
# budget, the code and the input. Lists each file with the ContractId it gives.
envelopes="$(mktemp -d)"
trap 'rm -rf "$envelopes"' EXIT
python3 -I - "$contracts" "$envelopes" "$chain" > "$envelopes/list" <<'EOF'
import hashlib, os, struct, sys
contracts, out, chain = sys.argv[1], sys.argv[2], bytes.fromhex(sys.argv[3])
def tagged(tag, *parts):
    t = tag.encode()
    h = hashlib.sha256(b"Ting/" + struct.pack("<H", len(t)) + t)
    for p in parts: h.update(p)
    return h.digest()
# Daler's configuration, as deployed at height 443 of the production network.
daler = bytes.fromhex(
    "010500000044616c657203000000444c520800804884639b4a00000000000000000000e40b54"
    "0200000000000000000000009005100000000000000000000000000000000000000000000000"
    "0000000000000000000000000000000000000000000000000000000000000000000000000000")
deploys = [(name, b"") for name in ("policy-secp256k1", "policy-p256", "policy-multisig-secp256k1", "id-registry", "batch")]
deploys.append(("coin", daler))
for name, data in deploys:
    code = open(os.path.join(contracts, name + ".wasm"), "rb").read()
    tx = bytes([1, 1]) + struct.pack("<4Q", 50_000_000, 64, 16_777_216, 16_777_216)
    tx += struct.pack("<I", len(code)) + code + struct.pack("<I", len(data)) + data
    path = os.path.join(out, name + ".tx")
    open(path, "wb").write(tx)
    print(name, path, tagged("contract", chain, tagged("transaction", chain, tx), struct.pack("<Q", 0)).hex())
EOF

sent=0
while read -r name path id; do
  if curl -fsS -o /dev/null "http://$api/v1/contracts/$id" 2>/dev/null; then
    echo "$name: $id, there already"
  else
    "$ting" submit --data-dir "$dir" "$path" >/dev/null
    echo "$name: $id"
    sent=1
  fi
done < "$envelopes/list"
if [ "$sent" = 1 ]; then "$ting" mine --data-dir "$dir" >/dev/null; fi

echo
echo "The node's API: http://$api, chain $chain"
echo "Build the app for it:   TING_NODES=http://$api ./gradlew :app:installDebug"
echo "From an emulator:       adb reverse tcp:$((port + 1)) tcp:$((port + 1))"
echo "Mine blocks:            $ting mine --data-dir $dir --count 10"
