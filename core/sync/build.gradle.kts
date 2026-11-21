plugins {
    alias(libs.plugins.coffer.jvm.library)
}

dependencies {
    api(project(":core:crypto"))
    api(project(":core:storage"))
    api(project(":core:index"))

    // Sync logic is plain Kotlin and tested on the JVM against FakeStorageBackend (CLAUDE.md).
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The multi-part round trip moves a 512 MiB part through the in-memory fake.
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
}
