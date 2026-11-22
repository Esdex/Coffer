import java.util.Properties

plugins {
    alias(libs.plugins.coffer.android.library)
}

// Coffer's Android side below the UI: the Keystore (fingerprint unlock, device-bound secrets), TDLib on the
// phone (its native libraries, the network callback) and the index in SQLCipher. Its classes keep the
// packages of :core:vault, :core:tdlib and :core:index, which they are part of; only the Android
// dependencies are kept out of those.

// scripts/build-tdlib.sh writes the JNI libraries, the generated Java API and a STAMP into core/tdlib/dist/.
// It is not in git: it is a build output of pinned sources. :core:tdlib compiles the Java API; the
// libraries are packed from here.
val tdlibDir = rootProject.file("core/tdlib")
val tdlibPin = Properties().apply { tdlibDir.resolve("tdlib.properties").inputStream().use(::load) }
val distDir = tdlibDir.resolve("dist")

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Through the dsl type: the generated `android` accessor casts source sets to a legacy type and fails on AGP 9.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Device tests sign in to Telegram's test servers with the personal keys. Passed to
        // `am instrument` at run time, not packed into an APK, and never printed.
        localProperties.getProperty("COFFER_API_ID")?.let { testInstrumentationRunnerArguments["cofferApiId"] = it.trim() }
        localProperties.getProperty("COFFER_API_HASH")?.let { testInstrumentationRunnerArguments["cofferApiHash"] = it.trim() }
    }
    sourceSets.getByName("main") {
        jniLibs.directories += distDir.resolve("libs").path
    }
    // MigrationTest reads the index's schemas, which :core:database (a JVM module) keeps.
    sourceSets.getByName("androidTest") {
        assets.directories += rootProject.file("core/database/schemas").path
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
    api(project(":core:vault"))
    api(project(":core:tdlib"))
    api(project(":core:database"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher)

    androidTestImplementation(testFixtures(project(":core:storage")))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
