package me.esdex.coffer.desktop.drive

import me.esdex.coffer.desktop.vault.log
import java.io.DataInputStream
import java.io.EOFException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Previews of the drive's files taken back out of the desktop's thumbnail cache, [root] (Esdex, 2026-11-23). Dolphin
 * shows previews of the drive once its "remote files" limit allows them, and KIO then keeps each one as a PNG in the
 * cache, unencrypted, by the file's address: seen on Esdex's computer, 11 photos' previews within a minute. KIO has no
 * way to be told not to for one mount, so each preview of a file under [drive] is deleted as soon as it is written,
 * and whatever is left is swept at the start and when the drive goes.
 *
 * A preview is the drive's by its Thumb::URI, as the freedesktop thumbnail spec has every one carry it; the rest of
 * the cache is left alone, and Coffer and Coffer Dev each take only their own drive's.
 */
class PreviewSweeper(private val root: Path, drive: Path) : AutoCloseable {
    private val prefix = "file://" + drive.toAbsolutePath().toString().trimEnd('/') + "/"

    @Volatile
    private var watcher: WatchService? = null

    /** Deletes every preview of the drive in the cache; how many it deleted. */
    @Synchronized
    fun sweep(): Int {
        var deleted = 0
        for (dir in folders()) for (file in pngs(dir)) if (take(file)) deleted++
        if (deleted > 0) log("removed $deleted previews of the drive from the thumbnail cache")
        return deleted
    }

    /** Deletes each preview of the drive as it comes, until [close]. */
    fun watch() {
        val service = runCatching { FileSystems.getDefault().newWatchService() }.getOrElse {
            log("cannot watch the thumbnail cache (${it.javaClass.simpleName})")
            return
        }
        watcher = service
        Files.createDirectories(root)
        val keys = HashMap<WatchKey, Path>()
        fun register(dir: Path) {
            // Modified too: a writer that writes in place, not by renaming, is seen first with an empty file.
            runCatching { keys[dir.register(service, ENTRY_CREATE, ENTRY_MODIFY)] = dir }
        }
        register(root)
        folders().forEach(::register)
        thread(name = "previews", isDaemon = true) {
            try {
                while (true) {
                    val key = service.take()
                    val dir = keys[key] ?: continue
                    for (event in key.pollEvents()) {
                        val path = dir.resolve(event.context() as? Path ?: continue)
                        if (path.isDirectory()) {
                            // A size or a fail/<app> folder made after the start, and what was written in it meanwhile.
                            register(path)
                            folders(path).forEach(::register)
                            sweep()
                        } else if (path.fileName.toString().endsWith(".png") && take(path)) {
                            log("removed a preview of the drive from the thumbnail cache")
                        }
                    }
                    if (!key.reset()) keys.remove(key)
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
            }
        }
    }

    override fun close() {
        runCatching { watcher?.close() }
    }

    /** Deletes [file] if it is a preview of the drive. */
    private fun take(file: Path): Boolean {
        val uri = try {
            uriOf(file)
        } catch (_: NoSuchFileException) {
            return false
        } catch (_: Exception) {
            null
        } ?: return false
        if (!uri.startsWith(prefix)) return false
        return runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
    }

    /** The size folders, normal to xx-large, and fail/<app>: the spec's layout. */
    private fun folders(under: Path = root): List<Path> {
        if (!under.isDirectory()) return emptyList()
        return runCatching { under.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList())
            .flatMap { if (it.fileName.toString() == "fail") listOf(it) + folders(it) else listOf(it) }
    }

    private fun pngs(dir: Path): List<Path> =
        runCatching { dir.listDirectoryEntries("*.png") }.getOrDefault(emptyList())

    companion object {
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val KEY = "Thumb::URI"
        private val TEXTS = setOf("tEXt", "zTXt", "iTXt")

        /** $XDG_CACHE_HOME/thumbnails, by default ~/.cache/thumbnails, as the spec places it. */
        fun cache(): Path {
            val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".cache")
            return base.resolve("thumbnails")
        }

        /**
         * The Thumb::URI of the PNG [file], from its text chunks: plain (tEXt), compressed (zTXt, which Qt writes for
         * longer texts) or international (iTXt). Read up to the image data only, before which the spec has it.
         */
        internal fun uriOf(file: Path): String? = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
            try {
                val signature = ByteArray(8)
                input.readFully(signature)
                if (!signature.contentEquals(PNG)) return null
                while (true) {
                    val length = input.readInt()
                    val type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
                    if (type == "IDAT" || type == "IEND" || length < 0 || length > MAX_TEXT) return null
                    val data = ByteArray(length).also(input::readFully)
                    input.readInt() // CRC
                    if (type in TEXTS) text(type, data)?.let { return it }
                }
            } catch (_: EOFException) {
            }
            null
        }

        private fun text(type: String, data: ByteArray): String? {
            val keyEnd = data.indexOf(0)
            if (keyEnd < 0 || String(data, 0, keyEnd, Charsets.ISO_8859_1) != KEY) return null
            return when (type) {
                "tEXt" -> String(data, keyEnd + 1, data.size - keyEnd - 1, Charsets.ISO_8859_1)
                "zTXt" -> String(inflate(data, keyEnd + 2), Charsets.ISO_8859_1)
                "iTXt" -> {
                    val compressed = data[keyEnd + 1].toInt() != 0
                    val language = data.indexOf(0, keyEnd + 3)
                    val translated = data.indexOf(0, language + 1)
                    val text = translated + 1
                    String(if (compressed) inflate(data, text) else data.copyOfRange(text, data.size), Charsets.UTF_8)
                }
                else -> null
            }
        }

        private fun inflate(data: ByteArray, from: Int): ByteArray =
            InflaterInputStream(data.inputStream(from, data.size - from)).use { it.readBytes() }

        private fun ByteArray.indexOf(byte: Int, from: Int = 0): Int = (from until size).firstOrNull { this[it].toInt() == byte } ?: -1

        // A thumbnail's texts are an address and a few numbers; anything far larger before the image data is no thumbnail.
        private const val MAX_TEXT = 1 shl 20
    }
}
