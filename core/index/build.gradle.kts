plugins {
    alias(libs.plugins.coffer.jvm.library)
    // The VaultIndex contract lives in test fixtures, so the Room index on each platform runs the same suite.
    `java-test-fixtures`
}

// What the vault's index is, without how it is kept: the Room database is :core:database.
dependencies {
    api(project(":core:crypto"))
    api(project(":core:storage"))

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.core)
}
