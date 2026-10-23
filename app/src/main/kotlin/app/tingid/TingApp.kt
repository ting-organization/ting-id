// The app's services, made once per process: the saved state, the chain
// caches, the vault and the chain. Screens reach them through `services`.
package app.tingid

import android.app.Application
import app.tingid.chain.unhex
import app.tingid.state.Activities
import app.tingid.state.Caches
import app.tingid.state.CoinData
import app.tingid.state.CoinIndexes
import app.tingid.state.FeeRates
import app.tingid.state.Keys
import app.tingid.state.Pending
import app.tingid.state.Phrases
import app.tingid.state.Registry
import app.tingid.state.Signing
import app.tingid.state.Workspace
import app.tingid.state.followKnownCoins
import app.tingid.text.statusWords
import app.tingid.state.Chain
import app.tingid.state.KeyRing
import app.tingid.state.Store
import app.tingid.state.defaultNodes
import app.tingid.vault.Legacy
import app.tingid.vault.Owner
import app.tingid.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class Services(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val storage = FileStorage(File(app.noBackupFilesDir, "state"))
    val caches = Caches(storage, scope)
    val store = Store(storage, defaultNodes(BuildConfig.TING_NODES), currentChain = caches::currentChain)
    val vault = Vault(app.noBackupFilesDir) { Owner.available(app) }
    val chain = Chain(store, caches, KeyRing { key -> vault.signingKey(key.id, unhex(key.pub)) }, scope)
    val activities = Activities(chain, storage, scope)
    val coinIndexes = CoinIndexes(chain, storage, scope)
    val coins = CoinData(chain, activities, scope)
    val workspace = Workspace(chain, coins, scope)
    val phrases = Phrases(vault)
    val keys = Keys(store, caches, vault, phrases, forgetScans = activities::forget, forgetCoinIndexes = coinIndexes::forget)
    val registry = Registry(chain, store)
    val signing = Signing(chain)
    val pending = Pending(chain, scope, ::statusWords)
    val feeRates = FeeRates(chain, scope)

    /**
     * Ting ID 1 left data on this phone, now removed: the app says so once. Its
     * leftovers are looked for only on the first start of this version.
     */
    val removedVersion1: Boolean = if (storage.read(STARTED) == null) {
        val found = Legacy.removeVersion1(app)
        storage.write(STARTED, "2")
        found
    } else {
        false
    }

    init {
        store.load()
        caches.openLast()
        // The chain's known coins are followed once the nodes name the chain.
        followKnownCoins(chain, scope)
    }

    private companion object {
        const val STARTED = "started"
    }
}

class TingApp : Application() {
    lateinit var services: Services
        private set

    override fun onCreate() {
        super.onCreate()
        services = Services(this)
    }
}
