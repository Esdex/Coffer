plugins {
    alias(libs.plugins.coffer.android.library)
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:index"))
}
