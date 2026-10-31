package app.xinghe.tv.wallpaper

import android.content.Context
import java.io.File

/** One file a phone has sent that is neither a picture nor a film: kept as it arrived. */
class ReceivedFile(
    val id: String,
    val file: File,
    val name: String,
    val size: Long,
    /** When it arrived, newest first. */
    val at: Long,
) {
    val apk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
}

/**
 * What a phone sends that is neither a wallpaper nor a film — a document, an app — kept in the
 * app's own folder, newest first. Nothing here is part of how Home looks; the sheet that lists it
 * is the only way in, and an APK is offered to the system's installer from there.
 */
object Uploads {

    private const val MAX_KEPT = 60

    fun dir(context: Context): File = File(context.filesDir, "uploads").apply { mkdirs() }

    /**
     * Copies [source] in under [name], which a second file of the same name gets a number after
     * rather than overwriting. Returns what was kept, or null when it could not be saved.
     */
    @Synchronized
    fun add(context: Context, source: File, name: String): ReceivedFile? {
        val clean = name.trim().trimStart('.').ifEmpty { "file" }.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
        val dir = dir(context)
        val target = runCatching { place(dir, clean) }.getOrNull() ?: return null
        return runCatching {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            ReceivedFile(target.name, target, clean, target.length(), System.currentTimeMillis())
        }.getOrElse {
            target.delete()
            null
        }.also { trim(context) }
    }

    fun all(context: Context): List<ReceivedFile> = dir(context).listFiles()?.mapNotNull { f ->
        if (!f.isFile) return@mapNotNull null
        // The name as it was sent, without the number a second one of that name was given.
        ReceivedFile(f.name, f, f.name.substringBefore("__").ifEmpty { f.name }, f.length(), f.lastModified())
    }?.sortedByDescending { it.at } ?: emptyList()

    @Synchronized
    fun delete(context: Context, id: String) {
        File(dir(context), id).takeIf { it.isFile }?.delete()
    }

    /** Oldest first, so what arrived last is never the thing thrown out. */
    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        for (i in 0 until (files.size - MAX_KEPT)) files[i].delete()
    }

    private fun place(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2..99) {
            val next = File(dir, "${stem}__$n$ext")
            if (!next.exists()) return next
        }
        return File(dir, "$stem${System.currentTimeMillis()}$ext")
    }
}
