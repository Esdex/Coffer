plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.coffer.android.compose)
    alias(libs.plugins.coffer.hilt)
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:sync"))
    implementation(project(":core:vault"))
    implementation(project(":core:tdlib"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
}
