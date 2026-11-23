import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
val release = providers.gradleProperty("coffer.release").isPresent
val publicApiKeys = Properties().apply {
    rootProject.file("app/public-api-keys.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    val values = mapOf(
        "VERSION" to version.toString(),
        "VARIANT" to if (release) "release" else "dev",
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
    runtimeOnly(libs.slf4j.nop)

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

// TDLib for the packaged app, in its resources, which its launcher puts on the library path.
val tdlibResources = tasks.register<Sync>("tdlibResources") {
    from(rootProject.file("core/tdlib/dist-desktop/linux-x86_64")) { include("libtdjni.so") }
    into(layout.buildDirectory.dir("appResources/linux-x64"))
}

compose.desktop {
    application {
        mainClass = "me.esdex.coffer.desktop.MainKt"
        // jfuse and TDLib's JNI library are loaded from the class path, not a named module.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // The windows' class is set from AWT's own field (Main.kt, nameWindows).
        jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
        // The packaged app: TDLib in its own resources, then the system's libraries, where jfuse finds libfuse3.
        // Running from Gradle sets its own (run, below).
        jvmArgs += "-Djava.library.path=\$APPDIR/resources:/usr/lib64:/lib64:/lib:/usr/lib"
        // jpackage builds the package; the JBR Gradle runs on has none: -Pcoffer.packagingJdk=/usr/lib/jvm/<a JDK 25+>.
        providers.gradleProperty("coffer.packagingJdk").orNull?.let { javaHome = it }
        nativeDistributions {
            // The app as a folder, its own Java inside: bin/coffer, lib/app, lib/runtime. Archived by packageTarball.
            targetFormats(TargetFormat.AppImage)
            packageName = "coffer"
            packageVersion = version.toString()
            description = "Encrypted file vault in Telegram's Saved Messages"
            vendor = "Esdex"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            // As suggestRuntimeModules has them (2026-11-23).
            modules("java.instrument", "java.sql", "jdk.security.auth", "jdk.unsupported")
            linux { iconFile.set(file("icons/app/coffer-256.png")) }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(tdlibResources) }

// Running from Gradle: TDLib where scripts/build-tdlib-linux.sh puts it, ahead of the JVM's own path.
// Compose registers it after this script runs, hence matching.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val tdlib = rootProject.file("core/tdlib/dist-desktop/linux-x86_64")
    val path = "-Djava.library.path=$tdlib${File.pathSeparator}${System.getProperty("java.library.path")}"
    doFirst { jvmArgs = jvmArgs.orEmpty().map { if (it.startsWith("-Djava.library.path=")) path else it } }
}

// The release archive, for GitHub and for packagers (the AUR): the app folder with what a system install puts beside
// it, the menu entry and the icons, under share/. Coffer, never Coffer Dev: it needs -Pcoffer.release.
val packageTarball = tasks.register<Tar>("packageTarball") {
    dependsOn("createDistributable")
    val isRelease = release
    doFirst { check(isRelease) { "the release archive is Coffer, not Coffer Dev: pass -Pcoffer.release" } }
    val name = "coffer-$version-linux-x86_64"
    archiveFileName.set("$name.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    compression = Compression.GZIP
    // The launcher and Java's own tools stay runnable: Gradle otherwise makes every file rw-r--r--.
    useFileSystemPermissions()
    into(name) {
        from(layout.buildDirectory.dir("compose/binaries/main/app/coffer"))
        from(rootProject.file("LICENSE"))
        into("share/applications") { from(rootProject.file("packaging/linux/me.esdex.coffer.desktop")) }
        into("share/icons/hicolor/scalable/apps") {
            from(rootProject.file("assets/coffer-logo.svg")) { rename { "me.esdex.coffer.svg" } }
        }
        for (size in listOf(16, 22, 24, 32, 48, 64, 128, 256, 512)) {
            into("share/icons/hicolor/${size}x$size/apps") {
                from(file("icons/app/coffer-$size.png")) { rename { "me.esdex.coffer.png" } }
            }
        }
    }
}
