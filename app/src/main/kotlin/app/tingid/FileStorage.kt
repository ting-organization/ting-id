// The app's files: its saved state and chain caches, in storage that is never
// backed up (the manifest also leaves the app out of backups and transfers).
package app.tingid

import android.util.AtomicFile
import app.tingid.state.TextStorage
import java.io.File
import java.io.FileNotFoundException

class FileStorage(private val dir: File) : TextStorage {
    init {
        dir.mkdirs()
    }

    private fun file(name: String): File {
        require(Regex("^[A-Za-z0-9._-]{1,128}$").matches(name) && !name.startsWith(".")) { "not a file name" }
        return File(dir, name)
    }

    override fun read(name: String): String? = try {
        AtomicFile(file(name)).readFully().decodeToString()
    } catch (e: FileNotFoundException) {
        null
    }

    override fun write(name: String, text: String) {
        val atomic = AtomicFile(file(name))
        val out = atomic.startWrite()
        try {
            out.write(text.encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    override fun delete(name: String) {
        AtomicFile(file(name)).delete()
    }

    override fun names(): List<String> = dir.list()?.filter { !it.endsWith(".new") && !it.endsWith(".bak") } ?: emptyList()
}
