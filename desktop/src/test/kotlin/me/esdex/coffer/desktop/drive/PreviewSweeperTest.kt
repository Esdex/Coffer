package me.esdex.coffer.desktop.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

class PreviewSweeperTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val drive = Path.of("/run/user/1000/coffer-dev")

    /** A thumbnail as the JDK's own PNG writer makes it, with Thumb::URI in a chunk of [kind]. */
    private fun thumbnail(file: Path, uri: String, kind: String) {
        Files.createDirectories(file.parent)
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
        val text = when (kind) {
            "tEXt" -> IIOMetadataNode("tEXtEntry").apply { setAttribute("keyword", "Thumb::URI"); setAttribute("value", uri) }
            "zTXt" -> IIOMetadataNode("zTXtEntry").apply {
                setAttribute("keyword", "Thumb::URI"); setAttribute("text", uri); setAttribute("compressionMethod", "deflate")
            }
            else -> IIOMetadataNode("iTXtEntry").apply {
                setAttribute("keyword", "Thumb::URI"); setAttribute("compressionFlag", (kind == "iTXt-z").toString())
                setAttribute("compressionMethod", "0"); setAttribute("languageTag", ""); setAttribute("translatedKeyword", "")
                setAttribute("text", uri)
            }
        }
        val chunk = IIOMetadataNode(kind.take(4)).apply { appendChild(text) }
        val root = IIOMetadataNode("javax_imageio_png_1.0").apply { appendChild(chunk) }
        metadata.mergeTree("javax_imageio_png_1.0", root)
        Files.newOutputStream(file).use { out ->
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(IIOImage(image, null, metadata))
            }
        }
        writer.dispose()
    }

    @Test
    fun theUriIsReadFromEveryKindOfTextChunk() {
        val root = folder.root.toPath()
        for (kind in listOf("tEXt", "zTXt", "iTXt", "iTXt-z")) {
            val file = root.resolve("$kind.png")
            thumbnail(file, "file:///run/user/1000/coffer-dev/Photos/a%20b.jpg", kind)
            assertEquals(kind, "file:///run/user/1000/coffer-dev/Photos/a%20b.jpg", PreviewSweeper.uriOf(file))
        }
        val plain = root.resolve("plain.png")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", plain.toFile())
        assertNull(PreviewSweeper.uriOf(plain))
        val notPng = root.resolve("not.png").also { Files.writeString(it, "hello") }
        assertNull("shorter than a signature", PreviewSweeper.uriOf(notPng))
        val cut = root.resolve("cut.png").also { Files.write(it, Files.readAllBytes(root.resolve("zTXt.png")).copyOf(40)) }
        assertNull("cut off in a chunk", PreviewSweeper.uriOf(cut))
    }

    @Test
    fun onlyTheDrivesPreviewsGoWhereverTheyAre() {
        val cache = folder.root.toPath().resolve("thumbnails")
        val ours = listOf(cache.resolve("x-large/1.png"), cache.resolve("normal/2.png"), cache.resolve("fail/kde-thumbnailer/3.png"))
        ours.forEachIndexed { i, file -> thumbnail(file, "file:///run/user/1000/coffer-dev/f$i.jpg", listOf("zTXt", "tEXt", "iTXt")[i]) }
        val theirs = mapOf(
            cache.resolve("x-large/home.png") to "file:///home/someone/photo.jpg",
            cache.resolve("large/release.png") to "file:///run/user/1000/coffer/photo.jpg",
            cache.resolve("large/beside.png") to "file:///run/user/1000/coffer-devices/photo.jpg",
        )
        theirs.forEach { (file, uri) -> thumbnail(file, uri, "zTXt") }
        val notPng = cache.resolve("large/other.png").also { Files.writeString(it, "file:///run/user/1000/coffer-dev/x") }

        assertEquals(3, PreviewSweeper(cache, drive).sweep())
        for (file in ours) assertFalse(file.toString(), Files.exists(file))
        for (file in theirs.keys + listOf(notPng)) assertTrue(file.toString(), Files.exists(file))
        assertEquals(0, PreviewSweeper(cache, drive).sweep())
    }

    private fun gone(vararg files: Path) {
        val deadline = System.currentTimeMillis() + 10_000
        while (files.any(Files::exists) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun aPreviewWrittenWhileWatchingGoesAtOnce() {
        val cache = folder.root.toPath().resolve("thumbnails")
        Files.createDirectories(cache.resolve("large"))
        PreviewSweeper(cache, drive).use { sweeper ->
            sweeper.watch()
            val home = cache.resolve("large/home.png").also { thumbnail(it, "file:///home/someone/a.jpg", "zTXt") }
            // KIO writes to a temporary name and renames it into place.
            val temp = cache.resolve("large/.tmp-ours")
            thumbnail(temp, "file:///run/user/1000/coffer-dev/a.jpg", "zTXt")
            val ours = cache.resolve("large/ours.png")
            Files.move(temp, ours, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            // A size folder made after the watch began, and a preview written in it once it is watched.
            val later = cache.resolve("xx-large/later.png").also { thumbnail(it, "file:///run/user/1000/coffer-dev/b.jpg", "tEXt") }
            gone(ours, later)
            assertFalse("renamed into place", Files.exists(ours))
            assertFalse("in a folder made later", Files.exists(later))
            val next = cache.resolve("xx-large/next.png").also { thumbnail(it, "file:///run/user/1000/coffer-dev/c.jpg", "zTXt") }
            gone(next)
            assertFalse("the next one in that folder, written in place", Files.exists(next))
            assertTrue(Files.exists(home))
        }
    }
}
