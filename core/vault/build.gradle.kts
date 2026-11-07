plugins {
    alias(libs.plugins.coffer.android.library)
}

dependencies {
    api(project(":core:crypto"))
    api(project(":core:storage"))
    // Password strength (SPEC §7, step 4), offline.
    implementation(libs.zxcvbn)
    implementation(libs.androidx.biometric)

    // The vault logic is plain Kotlin, so its tests run on the JVM with the desktop libsodium.
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.kotlinx.coroutines.test)
}
