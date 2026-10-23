// The phone's own check of its owner: fingerprint or face (strong biometrics
// only), or the screen lock's PIN, pattern or password. Ting ID asks it when
// it opens and before a payment goes (Settings → Security); the keystore asks
// it itself, bound to the key, before the vault shows a recovery phrase.
package app.tingid.vault

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OwnerCheck { Confirmed, Cancelled, Failed, Unavailable }

object Owner {
    private const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    @Volatile
    private var running = 0

    /** When the phone last confirmed its owner (ms since boot-independent epoch), 0 for never. */
    @Volatile
    var confirmedAt = 0L
        private set

    /** Whether a check is on screen: the PIN screen is a screen of its own, so the app seems to leave and come back while it shows. */
    val checking: Boolean get() = running > 0

    /** Whether the phone can check its owner: it has a screen lock. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Asks the phone to check its owner; `title` names the check, `subtitle` says what it is for. */
    suspend fun check(activity: FragmentActivity, title: String, subtitle: String? = null): OwnerCheck {
        if (!available(activity)) return OwnerCheck.Unavailable
        return try {
            prompt(activity, title, subtitle, null)
            OwnerCheck.Confirmed
        } catch (e: NotConfirmed) {
            if (e.message == CANCELLED) OwnerCheck.Cancelled else OwnerCheck.Failed
        }
    }

    /** The vault's prompt: the keystore lets `cipher` work once the owner confirms. */
    fun prompt(activity: FragmentActivity): OwnerPrompt = OwnerPrompt { title, subtitle, cipher ->
        prompt(activity, title, subtitle, cipher) ?: throw NotConfirmed("the phone did not confirm it is you")
    }

    private const val CANCELLED = "cancelled"

    private suspend fun prompt(activity: FragmentActivity, title: String, subtitle: String?, cipher: Cipher?): Cipher? =
        suspendCancellableCoroutine { cont ->
            running++
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    running--
                    confirmedAt = System.currentTimeMillis()
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher ?: cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    running--
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (cont.isActive) cont.resumeWithException(NotConfirmed(if (cancelled) CANCELLED else "the phone did not confirm it is you"))
                }

                // A finger not recognized: the prompt stays and the person tries again.
                override fun onAuthenticationFailed() {}
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(ALLOWED)
                .setConfirmationRequired(true)
                .build()
            if (cipher == null) prompt.authenticate(info) else prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
