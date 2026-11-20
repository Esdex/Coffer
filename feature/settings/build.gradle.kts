plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.coffer.android.compose)
    alias(libs.plugins.coffer.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Which store the build is for: the Play build carries no donation page (src/playstore), as Arcanum's.
android {
    flavorDimensions += "store"
    productFlavors {
        create("fdroid") { dimension = "store" }
        create("playstore") { dimension = "store" }
    }
}

dependencies {
    implementation(project(":core:ui"))
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
    implementation(libs.kotlinx.serialization.json)
}
