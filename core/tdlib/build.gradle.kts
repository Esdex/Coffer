import java.util.Properties

plugins {
    alias(libs.plugins.coffer.android.library)
}

// scripts/build-tdlib.sh writes the JNI libraries, the generated Java API and a STAMP into dist/.
// dist/ is not in git: it is a build output of pinned sources.
val tdlibPin = Properties().apply { file("tdlib.properties").inputStream().use(::load) }
val distDir = file("dist")

// Through the dsl type: the generated `android` accessor casts source sets to a legacy type and fails on AGP 9.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main") {
        java.directories += distDir.resolve("java").path
        jniLibs.directories += distDir.resolve("libs").path
    }
}

val checkTdlibDist = tasks.register("checkTdlibDist") {
    val pinned = tdlibPin.stringPropertyNames().associateWith { tdlibPin.getProperty(it) }
    val stampFile = distDir.resolve("STAMP")
    doLast {
        if (!stampFile.exists()) {
            logger.warn("TDLib is not built yet. Run scripts/build-tdlib.sh (needed from stage 2 on).")
            return@doLast
        }
        val stamp = Properties().apply { stampFile.inputStream().use(::load) }
        val stale = pinned.filter { (key, value) -> stamp.getProperty(key) != value }.keys
        if (stale.isNotEmpty()) {
            throw GradleException(
                "core/tdlib/dist was built from different inputs (${stale.joinToString()}). " +
                    "Rerun scripts/build-tdlib.sh."
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkTdlibDist) }

dependencies {
    api(project(":core:storage"))
    // The generated TdApi.java uses @IntDef.
    implementation(libs.androidx.annotation)
}
