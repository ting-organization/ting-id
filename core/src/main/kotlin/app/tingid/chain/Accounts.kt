// Accounts and signing (wiki: programs/conventions, accounts and signed messages).
package app.tingid.chain

const val TAG_POLICY_ACCOUNT = "app/account-v1/policy"
const val TAG_CONTRACT_ACCOUNT = "app/account-v1/contract"
const val TAG_SIGN = "app/sign-v1"

/** `H("app/account-v1/policy", policy ‖ Bytes(params))`. */
fun policyAccount(policy: ByteArray, params: ByteArray): ByteArray =
    taggedHash(TAG_POLICY_ACCOUNT, policy, u32le(params.size), params)

fun contractAccount(contract: ByteArray): ByteArray = taggedHash(TAG_CONTRACT_ACCOUNT, contract)

/** What signature policies sign for an application message. */
fun signingDigest(message: ByteArray): ByteArray = taggedHash(TAG_SIGN, message)

/** A secp256k1 signature over the signing digest of `message`. */
fun signMessage(secret: ByteArray, message: ByteArray): ByteArray = signDigest(secret, signingDigest(message))

/** Policy result (wiki: programs/conventions, policy interface): the required delay, or null when not authorized. */
fun authorizedDelay(result: ByteArray): Long? {
    if (result.size == 1 && result[0] == 1.toByte()) return 0
    if (result.size == 9 && result[0] == 1.toByte()) {
        var d = 0L
        for (i in 8 downTo 1) d = (d shl 8) or (result[i].toLong() and 0xff)
        return if (d > 0) d else null
    }
    return null
}
