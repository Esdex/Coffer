import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Coffer for the desktop: the vault as a drive, with the transfers in the tray (Esdex, 2026-11-21).
// JVM 25: jfuse calls libfuse through the Foreign Function & Memory API and needs it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.jfuse)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
}

compose.desktop {
    application {
        mainClass = "me.esdex.coffer.desktop.MainKt"
        // jfuse is on the class path, not a named module.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
