plugins {
    alias(libs.plugins.coffer.jvm.library)
    // The StorageBackend contract lives in test fixtures, so the TDLib backend's device tests run the same suite.
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
