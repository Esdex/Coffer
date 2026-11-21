plugins {
    alias(libs.plugins.coffer.android.library)
}

// The vault's Android side: unlocking with a fingerprint through the Keystore. Its classes keep the
// package of :core:vault, which they are part of; only the Android dependency is kept out of it.
dependencies {
    api(project(":core:vault"))
    implementation(libs.androidx.biometric)
}
