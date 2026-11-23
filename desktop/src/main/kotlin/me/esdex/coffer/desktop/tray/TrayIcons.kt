package me.esdex.coffer.desktop.tray

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Coffer's chest in the tray, in the look that says where it stands (Esdex, 2026-11-22): in colour when the
 * vault is open, grey when it is locked or not reachable, with a blue arrow while something goes to or comes
 * from Telegram, with an orange mark when Telegram cannot be reached (Esdex, 2026-11-23). Drawn from the PNGs of desktop/icons/coffer-tray.svg, in every size there is: the panel
 * picks the one that fits.
 *
 * Monochrome, if the user picks it (Esdex, 2026-11-22): the chest by name, a symbolic SVG the panel paints in its
 * own text colour, as it paints Breeze's icons, faded when locked; only the arrow keeps its colour, in the same SVG.
 */
object TrayIcons {
    enum class Look { OPEN, LOCKED, BUSY, OFFLINE }

    private val sizes = listOf(16, 22, 24, 32, 48, 64, 128)

    private val chests: List<BufferedImage> by lazy {
        sizes.map { size ->
            val stream = checkNotNull(TrayIcons::class.java.getResourceAsStream("chest-$size.png")) { "no tray icon of $size px" }
            stream.use { ImageIO.read(it) }.let(::argb)
        }
    }

    private val looks = HashMap<Look, List<BufferedImage>>()

    private val symbolic = listOf("coffer-tray-symbolic", "coffer-tray-locked-symbolic", "coffer-tray-busy-symbolic", "coffer-tray-offline-symbolic")

    /**
     * The symbolic SVGs, written under [dir] where the panel looks for a named icon: as an icon theme (hicolor) and
     * as plain files, as hosts differ in which they read. Returns the path to hand over as IconThemePath.
     */
    fun install(dir: File): String {
        val apps = File(dir, "hicolor/scalable/apps").apply { mkdirs() }
        for (name in symbolic) {
            val svg = checkNotNull(TrayIcons::class.java.getResourceAsStream("$name.svg")) { "no $name.svg" }.use { it.readBytes() }
            File(apps, "$name.svg").writeBytes(svg)
            File(dir, "$name.svg").writeBytes(svg)
        }
        return dir.absolutePath
    }

    /** What the tray shows for [look]: in colour, or [monochrome] by name from [themePath]. */
    fun tray(look: Look, monochrome: Boolean, themePath: String): KdeTray.Look = when {
        !monochrome -> KdeTray.Look.Pictures(of(look))
        look == Look.LOCKED -> KdeTray.Look.Named("coffer-tray-locked-symbolic", themePath)
        look == Look.BUSY -> KdeTray.Look.Named("coffer-tray-busy-symbolic", themePath)
        look == Look.OFFLINE -> KdeTray.Look.Named("coffer-tray-offline-symbolic", themePath)
        else -> KdeTray.Look.Named("coffer-tray-symbolic", themePath)
    }

    @Synchronized
    fun of(look: Look): List<BufferedImage> = looks.getOrPut(look) {
        when (look) {
            Look.OPEN -> chests
            Look.LOCKED -> chests.map(::grey)
            Look.BUSY -> chests.map { withBadge(it, BLUE, ::arrowUp) }
            Look.OFFLINE -> chests.map { withBadge(it, ORANGE, ::exclamation) }
        }
    }

    private fun argb(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }

    /** The chest in greys of its own lightness, its edges as they were. */
    private fun grey(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Lifted a little: plain luminance of the gold is a dull grey that sinks into a dark panel.
            val v = (0.299 * r + 0.587 * g + 0.114 * b).toInt().let { 60 + it * 3 / 4 }.coerceIn(0, 255)
            out.setRGB(x, y, (p and 0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v)
        }
        return out
    }

    /** The chest with a disc of [colour] in its lower right corner, [mark] drawn on it in white. */
    private fun withBadge(image: BufferedImage, colour: Color, mark: (Graphics2D, Double, Double, Double) -> Unit): BufferedImage {
        val out = argb(image)
        val s = image.width.toDouble()
        val d = s * 0.5
        val x = s - d
        val y = s - d
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // A dark rim, so the disc stands off the gold and off a light panel alike.
            color = Color(0x1B, 0x1E, 0x20)
            fill(Ellipse2D.Double(x - s * 0.04, y - s * 0.04, d + s * 0.08, d + s * 0.08))
            color = colour
            fill(Ellipse2D.Double(x, y, d, d))
            color = Color.WHITE
            stroke = BasicStroke((d * 0.13).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            mark(this, x, y, d)
            dispose()
        }
        return out
    }

    /** Something going to or coming from Telegram, on the disc of diameter [d] at [x], [y]. */
    private fun arrowUp(graphics: Graphics2D, x: Double, y: Double, d: Double) {
        val cx = x + d / 2
        val top = y + d * 0.24
        val bottom = y + d * 0.76
        val arm = d * 0.17
        graphics.draw(
            Path2D.Double().apply {
                moveTo(cx, bottom)
                lineTo(cx, top)
                moveTo(cx - arm, top + arm)
                lineTo(cx, top)
                lineTo(cx + arm, top + arm)
            },
        )
    }

    /** Telegram out of reach. */
    private fun exclamation(graphics: Graphics2D, x: Double, y: Double, d: Double) {
        val cx = x + d / 2
        graphics.draw(Line2D.Double(cx, y + d * 0.22, cx, y + d * 0.58))
        val r = d * 0.075
        graphics.fill(Ellipse2D.Double(cx - r, y + d * 0.77 - r, 2 * r, 2 * r))
    }

    // Breeze's highlight blue and its warning orange, as Plasma's own icons use them.
    private val BLUE = Color(0x3D, 0xAE, 0xE9)
    private val ORANGE = Color(0xF6, 0x74, 0x00)
}
