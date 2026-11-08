plugins {
    alias(libs.plugins.coffer.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:crypto"))
    api(project(":core:storage"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
