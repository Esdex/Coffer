import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Coffer for the desktop: the vault as a drive, with the transfers in the tray (Esdex, 2026-11-21).
// JVM 25: jfuse calls libfuse through the Foreign Function & Memory API and needs it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

// Coffer's own Telegram API keys, the ones the Android public build carries (app/public-api-keys.properties,
// in git on purpose), the version, and which Coffer it is: written into a resource the app reads at start.
// Coffer Dev unless -Pcoffer.release, which packaging passes: a build that forgets it keeps away from the
// installed Coffer's data rather than into it (Esdex, 2026-11-23).
val publicApiKeys = Properties().apply {
    rootProject.file("app/public-api-keys.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    val values = mapOf(
        "VERSION" to version.toString(),
        "VARIANT" to if (providers.gradleProperty("coffer.release").isPresent) "release" else "dev",
        "API_ID" to publicApiKeys.getProperty("COFFER_PUBLIC_API_ID").orEmpty().trim(),
        "API_HASH" to publicApiKeys.getProperty("COFFER_PUBLIC_API_HASH").orEmpty().trim(),
    )
    inputs.properties(values)
    outputs.dir(out)
    doLast {
        val file = out.get().file("me/esdex/coffer/desktop/build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(values.entries.joinToString("") { (key, value) -> "$key=$value\n" })
    }
}
sourceSets.main { resources.srcDir(buildInfo) }

dependencies {
    implementation(project(":core:tdlib"))
    implementation(project(":core:database"))
    implementation(project(":core:vault"))
    implementation(project(":core:sync"))
    implementation(libs.lazysodium.java)
    implementation(libs.sqlite.jdbc.crypt)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.zxing.core)
    implementation(libs.jfuse)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:index")))
}

// KeyringTest touches the session's real keyring, so only on request: -Pcoffer.keyringTest
tasks.test {
    systemProperty("coffer.keyringTest", providers.gradleProperty("coffer.keyringTest").isPresent.toString())
    // TrashProbeTest runs KDE's trash on the user's real session, so only on request: -Pcoffer.trashProbe
    systemProperty("coffer.trashProbe", providers.gradleProperty("coffer.trashProbe").isPresent.toString())
    // VaultDriveTest mounts the drive through jfuse, as the app does.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "me.esdex.coffer.desktop.MainKt"
        // jfuse and TDLib's JNI library are loaded from the class path, not a named module.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // TDLib for the desktop, built by scripts/build-tdlib-linux.sh, ahead of the JVM's own path, where
        // jfuse finds the system's libfuse3. Packaging comes later.
        val tdlib = rootProject.file("core/tdlib/dist-desktop/linux-x86_64")
        jvmArgs += "-Djava.library.path=$tdlib${File.pathSeparator}${System.getProperty("java.library.path")}"
    }
}
