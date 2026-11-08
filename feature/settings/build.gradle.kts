plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.coffer.android.compose)
    alias(libs.plugins.coffer.hilt)
}

dependencies {
    implementation(project(":core:vault"))
    implementation(project(":core:sync"))
    implementation(project(":core:tdlib"))
    // The integrity check and the rescan run on the browser's open session.
    implementation(project(":feature:browser"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
