import java.util.Properties

plugins {
    alias(libs.plugins.coffer.jvm.library)
}

// TDLib's Java API comes out of the TDLib build of pinned sources, not git: scripts/build-tdlib.sh writes it
// to dist/, scripts/build-tdlib-linux.sh to dist-desktop/. It is one file (the desktop script compares the
// two), so either will do. The native libraries are the platforms': :core:device packs Android's.
val tdlibPin = Properties().apply { file("tdlib.properties").inputStream().use(::load) }

/** The keys the Java API depends on; the other pins are for the native libraries. */
val apiPins = listOf("TDLIB_COMMIT", "TDLIB_VERSION")

fun stampOf(dist: File): Properties? =
    dist.resolve("STAMP").takeIf { it.exists() }?.let { stamp -> Properties().apply { stamp.inputStream().use(::load) } }

fun isCurrent(dist: File): Boolean =
    stampOf(dist)?.let { stamp -> apiPins.all { stamp.getProperty(it) == tdlibPin.getProperty(it) } } ?: false

val dists = listOf(file("dist"), file("dist-desktop"))
val apiDist: File? = dists.firstOrNull(::isCurrent)

sourceSets.main {
    apiDist?.let { java.srcDir(it.resolve("java")) }
}

val checkTdlibApi = tasks.register("checkTdlibApi") {
    val stale = dists.filter { stampOf(it) != null && !isCurrent(it) }.map { it.name }
    val found = apiDist != null
    doLast {
        if (found) return@doLast
        if (stale.isNotEmpty()) {
            throw GradleException(
                "core/tdlib/${stale.joinToString(" and core/tdlib/")} was built from another TDLib. " +
                    "Rerun scripts/build-tdlib.sh (or scripts/build-tdlib-linux.sh for the desktop).",
            )
        }
        logger.warn("TDLib is not built yet. Run scripts/build-tdlib.sh, or scripts/build-tdlib-linux.sh for the desktop.")
    }
}
tasks.named("compileJava") { dependsOn(checkTdlibApi) }
tasks.named("compileKotlin") { dependsOn(checkTdlibApi) }

dependencies {
    api(project(":core:storage"))
    // The generated TdApi.java uses @IntDef and @Nullable; androidx.annotation has a plain JVM build.
    implementation(libs.androidx.annotation)
}
