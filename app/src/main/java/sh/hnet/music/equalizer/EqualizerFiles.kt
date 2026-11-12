package sh.hnet.music.equalizer

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading and writing preset files the user picks (SPEC-equalizer §10): read once or written once,
 * no lasting permission. The details of a failure are shown only, behind the error's Show (never logged).
 */
object EqualizerFiles {
    sealed interface Read {
        class Done(val parsed: EqualizerText.Parsed) : Read
        class NotAPreset(val details: String) : Read
        class Unreadable(val details: String) : Read
    }

    suspend fun read(resolver: ContentResolver, uri: Uri): Read = withContext(Dispatchers.IO) {
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readNBytes(EqualizerText.MAX_BYTES + 1) } ?: return@withContext Read.Unreadable("No content")
        } catch (e: IOException) {
            return@withContext Read.Unreadable(describe(e))
        } catch (e: SecurityException) {
            return@withContext Read.Unreadable(e.javaClass.simpleName)
        }
        if (bytes.size > EqualizerText.MAX_BYTES) return@withContext Read.NotAPreset("Larger than ${EqualizerText.MAX_BYTES / 1024} kB")
        EqualizerText.parse(EqualizerText.decode(bytes))?.let { Read.Done(it) } ?: Read.NotAPreset("No Preamp or Filter lines")
    }

    /** Null when [text] was written; else what went wrong. */
    suspend fun write(resolver: ContentResolver, uri: Uri, text: String): String? = withContext(Dispatchers.IO) {
        try {
            // "wt": a file picked to be replaced doesn't keep its old end
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return@withContext "No content"
            null
        } catch (e: IOException) {
            describe(e)
        } catch (e: SecurityException) {
            e.javaClass.simpleName
        }
    }

    private fun describe(e: Exception) = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}
