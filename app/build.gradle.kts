import java.util.Properties

plugins {
    alias(libs.plugins.coffer.android.application)
    alias(libs.plugins.coffer.android.compose)
    alias(libs.plugins.coffer.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Same file scripts/build-tdlib.sh reads, so the APK packs exactly the ABIs that were built.
val tdlibPin = Properties().apply {
    rootProject.file("core/tdlib/tdlib.properties").inputStream().use(::load)
}

android {
    namespace = "me.esdex.coffer"

    defaultConfig {
        applicationId = "me.esdex.coffer"
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += tdlibPin.getProperty("ABIS").split(',')
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Keys come from local.properties; onboarding skips the api_id step (SPEC §2).
        create("personal") {
            dimension = "distribution"
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
        }
        // The user always enters the keys.
        create("public") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }

    // The whole :core:crypto test suite runs again on a device, against lazysodium-android: the same
    // oracle vectors, now through the native library the app ships.
    sourceSets.getByName("androidTest") {
        kotlin.directories += "../core/crypto/src/test/kotlin"
        resources.directories += "../core/crypto/src/test/resources"
    }
}

val apiId: String = localProperties.getProperty("COFFER_API_ID").orEmpty().trim()
val apiHash: String = localProperties.getProperty("COFFER_API_HASH").orEmpty().trim()
val apiIdValid = apiId.toIntOrNull()?.let { it > 0 } == true
val apiHashValid = apiHash.matches(Regex("[0-9a-fA-F]{32}"))

// Fails only the personal variants, so public builds and CI work without keys.
// Never prints the values themselves.
val validatePersonalApiKeys = tasks.register("validatePersonalApiKeys") {
    val idValid = apiIdValid
    val hashValid = apiHashValid
    doLast {
        val problems = buildList {
            if (!idValid) add("COFFER_API_ID is missing or not a positive integer")
            if (!hashValid) add("COFFER_API_HASH is missing or not 32 hex characters")
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The personal flavor needs Telegram API keys in local.properties:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nGet them at https://my.telegram.org, or build the public flavor instead."
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val personal = variant.productFlavors.contains("distribution" to "personal")
        val bundled = personal && apiIdValid && apiHashValid
        variant.buildConfigFields?.apply {
            put("HAS_BUNDLED_API_KEYS", com.android.build.api.variant.BuildConfigField("boolean", bundled.toString(), null))
            put("API_ID", com.android.build.api.variant.BuildConfigField("int", if (bundled) apiId else "0", null))
            put("API_HASH", com.android.build.api.variant.BuildConfigField("String", if (bundled) "\"$apiHash\"" else "\"\"", null))
        }
        if (personal) {
            val preBuild = "pre${variant.name.replaceFirstChar(Char::titlecase)}Build"
            tasks.named { it == preBuild }.configureEach { dependsOn(validatePersonalApiKeys) }
        }
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:tdlib"))
    implementation(project(":core:vault"))
    implementation(project(":core:index"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // libsodium for Android. Its POM pulls the plain JNA jar, which has no Android native library;
    // the AAR of the same JNA version does.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.serialization.json)

    // The storage self-check runs the StorageBackend contract inside debug builds.
    debugImplementation(testFixtures(project(":core:storage")))
}
