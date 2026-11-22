plugins {
    alias(libs.plugins.coffer.jvm.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The index in Room, the same tables and code on Android and the desktop. Each opens it with its own
// SQLCipher: Android's in :core:device, the desktop's in :desktop.
dependencies {
    api(project(":core:index"))
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}
