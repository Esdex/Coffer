package me.esdex.coffer.core.ui.theme

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * How long things move, for the whole app: one scale, so a title, a page and a screen each move as the
 * same kind of thing moves everywhere else. Things go a step faster than they came: what leaves needs
 * no reading (Material's rule). Times in milliseconds.
 */
object Motion {
    /** Small things appearing in place: a hint, a badge, the seek ripple, a check box. */
    const val QUICK = 150

    /** Content changing in place: a title, a folder's list, a chip, a selection, controls showing. */
    const val STANDARD = 250

    /** Whole screens and panels coming in or going: Transfers, a settings page, a viewer, a bottom bar. */
    const val SCREEN = 350

    /** A colour drifting behind everything, slow enough not to be seen as a change: the player's tint. */
    const val AMBIENT = 700

    /** A drawing that shows itself once, as it is drawn: the storage ring. */
    const val REVEAL = 900

    /** One pass of the light over something still loading. */
    const val SHIMMER = 1_400

    /** The one curve for everything that moves from one place to another. */
    val Easing: Easing = EaseInOutCubic

    fun <T> quick(): TweenSpec<T> = tween(QUICK, easing = Easing)

    fun <T> standard(): TweenSpec<T> = tween(STANDARD, easing = Easing)

    fun <T> screen(): TweenSpec<T> = tween(SCREEN, easing = Easing)

    /** For what follows a measured value, like a position read a few times a second: no speeding up or slowing down. */
    fun <T> linear(durationMillis: Int): TweenSpec<T> = tween(durationMillis, easing = LinearEasing)
}

/**
 * The corners of the app, by what they round. Squares of about 42 dp (file tiles, covers in a row) share
 * one radius; anything fully rounded (buttons, capsules, thin bars) is a [pill], whatever its height.
 */
object Corners {
    /** A small mark on a tile: the file type on a preview. */
    val Badge = 5.dp

    /** A label or a hint over a picture. */
    val Small = 8.dp

    /** A file tile, a small cover, a code block. */
    val Tile = 12.dp

    /** A large picture: the app icon in About, the cover in the player. */
    val Large = 20.dp

    /** Dialogs, sheets and the outer corners of a group of rows (Material's extra large). */
    val Panel = 28.dp

    /** Where rows of one group meet. */
    val Inner = 6.dp

    /** Fully rounded at any height. */
    val pill = RoundedCornerShape(50)
}
