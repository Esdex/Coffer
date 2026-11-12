plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.coffer.android.compose)
    alias(libs.plugins.coffer.hilt)
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:tdlib"))
    implementation(project(":core:vault"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    // Draws the sign-in QR code. Pure Java, no Android or network code.
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
