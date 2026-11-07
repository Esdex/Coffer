plugins {
    alias(libs.plugins.coffer.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // @CborLabel and the CBOR builder options are still marked experimental.
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.cbor)

    // LazySodiumAdapter is written against the LazySodium base class that lazysodium-java and
    // lazysodium-android both ship. The app supplies the Android one at runtime, tests the JVM one.
    compileOnly(libs.lazysodium.java)
    // For NativeLong in the crypto_pwhash signature; lazysodium only declares JNA for runtime.
    compileOnly(libs.jna)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.kotlinx.serialization.json)
}
