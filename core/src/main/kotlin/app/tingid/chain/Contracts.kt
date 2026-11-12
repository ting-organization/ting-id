// The programs the app uses and their canonical ids on a chain (wiki:
// programs). Their code is embedded (resources, `contracts/*.wasm`, copied by
// scripts/sync-contracts.sh: the reference programs from the Ting
// repository's `programs`, and policy-webauthn, Ting ID Web's passkey policy,
// from that repository's `program.wasm`), so the app derives every one's id
// itself rather than taking a node's word for it.
package app.tingid.chain

import java.util.concurrent.ConcurrentHashMap

enum class ContractName(val file: String) {
    PolicySecp256k1("policy-secp256k1"),
    PolicyP256("policy-p256"),
    PolicyMultisigSecp256k1("policy-multisig-secp256k1"),
    IdRegistry("id-registry"),
    Coin("coin"),
    Batch("batch"),

    /** Not a reference program: Ting ID Web's, whose passkeys are keys of identities like any other. */
    PolicyWebauthn("policy-webauthn"),
}

object Contracts {
    private val code = ConcurrentHashMap<ContractName, ByteArray>()
    private val canonicalCache = ConcurrentHashMap<String, Canonical>()

    fun code(name: ContractName): ByteArray = code.getOrPut(name) {
        // An absolute path: the release build moves classes to other packages, which a relative one would follow.
        Contracts::class.java.getResourceAsStream("/app/tingid/chain/contracts/${name.file}.wasm")?.use { it.readBytes() }
            ?: error("the ${name.file} program is missing from the app")
    }.copyOf()

    /** The code hash a node reports for a contract running this program: `H("code", wasm)`. */
    fun codeHash(name: ContractName): String = taggedHash("code", code(name)).hex()

    /** Canonical ids of the standard contracts on `chain`; derived once per chain. */
    fun canonical(chain: ByteArray): Canonical = canonicalCache.getOrPut(chain.hex()) {
        Canonical(
            policySecp256k1 = canonicalId(chain, code(ContractName.PolicySecp256k1)),
            policyP256 = canonicalId(chain, code(ContractName.PolicyP256)),
            multisig = canonicalId(chain, code(ContractName.PolicyMultisigSecp256k1)),
            registry = canonicalId(chain, code(ContractName.IdRegistry)),
            batch = canonicalId(chain, code(ContractName.Batch)),
            policyWebauthn = canonicalId(chain, code(ContractName.PolicyWebauthn)),
        )
    }
}

class Canonical(
    val policySecp256k1: ByteArray,
    val policyP256: ByteArray,
    val multisig: ByteArray,
    val registry: ByteArray,
    val batch: ByteArray,
    /** Where policy-webauthn sits once deployed on the chain; a key of it does nothing until it is. */
    val policyWebauthn: ByteArray,
) {
    fun policyFor(scheme: Scheme): ByteArray = when (scheme) {
        Scheme.Secp256k1 -> policySecp256k1
        Scheme.P256 -> policyP256
        Scheme.Webauthn -> policyWebauthn
    }

    /** What a canonical policy verifies, if it is one of the standard ones. */
    fun kindOf(policy: ByteArray): PolicyKind? = when {
        equal(policy, policySecp256k1) -> PolicyKind.Secp256k1
        equal(policy, policyP256) -> PolicyKind.P256
        equal(policy, multisig) -> PolicyKind.Multisig
        equal(policy, registry) -> PolicyKind.Identity
        equal(policy, policyWebauthn) -> PolicyKind.Webauthn
        else -> null
    }
}

enum class PolicyKind {
    Secp256k1, P256, Multisig, Identity, Webauthn;

    val scheme: Scheme? get() = when (this) {
        Secp256k1 -> Scheme.Secp256k1
        P256 -> Scheme.P256
        Webauthn -> Scheme.Webauthn
        else -> null
    }
}
