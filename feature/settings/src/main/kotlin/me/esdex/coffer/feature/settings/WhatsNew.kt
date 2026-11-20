package me.esdex.coffer.feature.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What's new in each version, as Arcanum shows it (decision 2026-11-20): `assets/whatsnew.json`, short
 * entries of a kind each. The CHANGELOG stays the full account; this is what fits a phone screen.
 */
@Serializable
data class WhatsNew(val versions: List<WhatsNewVersion> = emptyList()) {
    /** The entries of the build that runs, by its version code. */
    fun current(versionCode: Long): WhatsNewVersion? = versions.firstOrNull { it.versionCode.toLong() == versionCode }

    /**
     * Versions before it, newest first. One above it, written ahead of its release, stays hidden until the
     * build catches up.
     */
    fun older(versionCode: Long): List<WhatsNewVersion> = versions.filter { it.versionCode < versionCode }.sortedByDescending { it.versionCode }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): WhatsNew = json.decodeFromString(serializer(), text)

        /** Empty rather than a crash if the asset is missing or damaged. */
        fun load(context: Context): WhatsNew =
            runCatching { context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) } }.getOrElse { WhatsNew() }

        const val ASSET = "whatsnew.json"
    }
}

@Serializable
data class WhatsNewVersion(val version: String, val versionCode: Int, val entries: List<WhatsNewEntry> = emptyList())

@Serializable
data class WhatsNewEntry(val type: String, val title: String, val description: String? = null) {
    /** New things first, then improvements, security and fixes; within a kind, as written. */
    val rank: Int get() = when (type) {
        IMPROVEMENT -> 1
        SECURITY -> 2
        FIX -> 3
        else -> 0
    }

    companion object {
        const val NEW = "new"
        const val IMPROVEMENT = "improvement"
        const val SECURITY = "security"
        const val FIX = "fix"
        val TYPES = setOf(NEW, IMPROVEMENT, SECURITY, FIX)
    }
}

fun List<WhatsNewEntry>.inDisplayOrder(): List<WhatsNewEntry> = sortedBy { it.rank }

/** The icon and colour of an entry's kind; an unknown kind is drawn as new, as it ranks. */
internal fun WhatsNewEntry.visuals(): Pair<ImageVector, Color> = when (type) {
    WhatsNewEntry.SECURITY -> Icons.Outlined.Security to Color(0xFF22C55E)
    WhatsNewEntry.IMPROVEMENT -> Icons.AutoMirrored.Outlined.TrendingUp to Color(0xFF3B82F6)
    WhatsNewEntry.FIX -> Icons.Outlined.BugReport to Color(0xFFEF4444)
    else -> Icons.Outlined.AutoAwesome to Color(0xFFFFC107)
}

/** Addresses written out in full become links; nothing else does, so "e.g." and version numbers stay text. */
private val LINK = Regex("""https?://\S+""")

/** Punctuation after an address ends the sentence, not the address. */
private const val TRAILING = ".,;:!?)"

@Composable
internal fun linkified(text: String): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var cursor = 0
        for (match in LINK.findAll(text)) {
            val shown = match.value.trimEnd { it in TRAILING }
            append(text.substring(cursor, match.range.first))
            withLink(LinkAnnotation.Url(shown, styles)) { append(shown) }
            cursor = match.range.first + shown.length
        }
        append(text.substring(cursor))
    }
}
