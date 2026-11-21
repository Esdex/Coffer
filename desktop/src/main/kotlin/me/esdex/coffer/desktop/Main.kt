package me.esdex.coffer.desktop

import me.esdex.coffer.desktop.tray.KdeTray
import me.esdex.coffer.desktop.tray.MenuEntry
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * Coffer in the tray with its drive mounted. For now the drive is [HelloDrive]; quitting unmounts it.
 * The mount point is under XDG_RUNTIME_DIR, which the system empties at logout, so nothing is left in
 * the home folder.
 */
fun main() {
    val runtime = System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set")
    val drive = HelloDrive.mount(Path.of(runtime, "coffer"))
    val quit = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { drive.close() } })
    val open = { ProcessBuilder("xdg-open", drive.at.toString()).start(); Unit }
    val tray = KdeTray(
        title = "Coffer",
        icon = listOf(22, 32, 64).map(::padlock),
        entries = listOf(
            MenuEntry.Action("Open Coffer drive", open),
            MenuEntry.Separator,
            MenuEntry.Action("Quit") { quit.countDown() },
        ),
        onActivate = open,
    )
    quit.await()
    tray.close()
    drive.close()
}

/** A padlock, drawn, until the desktop has the app's own icon. */
private fun padlock(size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val s = size.toDouble()
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        color = Color(0x3D7BD9)
        stroke = BasicStroke((s * 0.10).toFloat())
        draw(RoundRectangle2D.Double(s * 0.28, s * 0.10, s * 0.44, s * 0.50, s * 0.44, s * 0.44))
        fill(RoundRectangle2D.Double(s * 0.16, s * 0.42, s * 0.68, s * 0.50, s * 0.20, s * 0.20))
        color = Color.WHITE
        fill(Ellipse2D.Double(s * 0.43, s * 0.57, s * 0.14, s * 0.14))
        dispose()
    }
    return image
}
