plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.coffer.android.compose)
}

// The look shared by every screen: theme, appearance settings and the common components.
// Most of it comes from Arcanum, the same author's other app.
dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.haze)
    implementation(libs.lottie.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
