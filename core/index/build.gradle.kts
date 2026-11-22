plugins {
    alias(libs.plugins.coffer.jvm.library)
}

// What the vault's index is, without how it is kept: the Room database is :core:database.
dependencies {
    api(project(":core:crypto"))
    api(project(":core:storage"))
}
