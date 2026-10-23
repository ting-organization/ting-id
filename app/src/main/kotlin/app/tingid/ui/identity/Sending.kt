// Sending from a screen that changes an identity: one thing at a time, the
// owner's check on the screen, the sending itself in the app's scope.
package app.tingid.ui.identity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.tingid.state.Sent
import app.tingid.ui.LocalServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a screen is sending, one thing at a time: a second button pressed
 * meanwhile does nothing. `busy` names what is being sent; `error` says why
 * the last attempt failed, and the screen may set it for its other failures.
 */
class Sending internal constructor(private val app: CoroutineScope, private val ui: CoroutineScope) {
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    private val running = AtomicBoolean(false)

    /**
     * Sends under `label`. `check` runs first, on the screen: the owner's
     * check, false when the person stepped back, throwing when the phone did
     * not confirm. `submit` then runs in the app's scope, off the main thread,
     * and finishes even when the screen goes, as the transaction may be out
     * already; `opened` gets what it sent, only while the screen is still
     * there to go on from.
     */
    fun send(label: String, check: (suspend () -> Boolean)? = null, submit: suspend () -> Sent?, opened: (Sent) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        busy = label
        error = null
        ui.launch {
            var handed = false
            try {
                if (check != null && !check()) return@launch
                handed = true
                app.launch {
                    try {
                        val s = withContext(Dispatchers.IO) { submit() }
                        if (s != null) ui.launch { opened(s) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = e.message ?: "it could not be sent"
                    } finally {
                        done()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the phone did not confirm it is you"
            } finally {
                if (!handed) done()
            }
        }
    }

    private fun done() {
        running.set(false)
        busy = null
    }
}

@Composable
fun rememberSending(): Sending {
    val services = LocalServices.current
    val ui = rememberCoroutineScope()
    return remember(services, ui) { Sending(services.scope, ui) }
}
