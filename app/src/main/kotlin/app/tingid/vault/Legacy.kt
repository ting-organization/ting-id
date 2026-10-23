// Ting ID 1 (the Expo build) kept its keys in expo-secure-store and its state
// in AsyncStorage, under the same app id. Ting ID 2 starts fresh: on its first
// start it removes what version 1 left, destroying its keystore keys first so
// that no copy of a sealed secret could ever be opened again, and tells the
// person once to restore with their recovery phrase.
package app.tingid.vault

import android.content.Context
import java.io.File
import java.security.KeyStore

object Legacy {
    /** Whether version 1 left anything on this phone; removes it all. */
    fun removeVersion1(context: Context): Boolean {
        var found = false
        val keystore = KeyStore.getInstance(Vault.ANDROID_KEYSTORE).apply { load(null) }
        for (alias in keystore.aliases().toList()) {
            if (alias.startsWith(Vault.PREFIX)) continue
            keystore.deleteEntry(alias)
            found = true
        }
        val data = context.dataDir
        val leftovers = listOf(
            File(data, "shared_prefs/SecureStore.xml"),
            File(data, "databases/RKStorage"),
            File(data, "databases/RKStorage-journal"),
            File(data, "databases/RKStorage-wal"),
            File(data, "databases/RKStorage-shm"),
            File(data, "files/ExponentExperienceData"),
        )
        for (f in leftovers) if (f.exists()) {
            found = true
            f.deleteRecursively()
        }
        return found
    }
}
