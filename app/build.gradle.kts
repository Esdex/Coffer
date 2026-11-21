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
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += tdlibPin.getProperty("ABIS").split(',')
        }
    }

    flavorDimensions += listOf("distribution", "store")
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
        // GitHub Releases and F-Droid: the same build, with the donation page.
        create("fdroid") {
            dimension = "store"
        }
        // Google Play: no donation page, as Play's payments policy asks.
        create("playstore") {
            dimension = "store"
        }
    }

    // The release key, as Arcanum keeps its own: outside git, its path and passwords in local.properties.
    // Without them a release comes out unsigned, as F-Droid builds it and signs it itself.
    val releaseKey = localProperties.getProperty("COFFER_KEYSTORE_PATH")?.trim()?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey)
                storePassword = localProperties.getProperty("COFFER_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("COFFER_KEY_ALIAS")
                keyPassword = localProperties.getProperty("COFFER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // An app of its own, beside the release on the same phone: development never touches the vault in
        // daily use, which lives under the release's package and data (Esdex, 2026-11-14).
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 on: smaller, without the test helpers and full names a release had no use for (audit 1, Info).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }

    // F-Droid builds Coffer itself and ships the published APK only if its own build matches it byte for
    // byte. The dependency list AGP would sign into the APK is encrypted to Google and F-Droid refuses
    // extra signing blocks, so it stays out, as in Arcanum.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

// The public build's built-in keys: a developer key of their own, so the personal one stays personal.
// In git (public-api-keys.properties) and read from nowhere else, so every build of a commit, F-Droid's
// included, has the same keys and the same bytes. Without the file the public build asks every user for keys.
val publicApiKeys = Properties().apply {
    file("public-api-keys.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val publicApiId: String = publicApiKeys.getProperty("COFFER_PUBLIC_API_ID").orEmpty().trim()
val publicApiHash: String = publicApiKeys.getProperty("COFFER_PUBLIC_API_HASH").orEmpty().trim()
val publicKeysGiven = publicApiId.isNotEmpty() || publicApiHash.isNotEmpty()
val publicApiKeysValid = publicApiId.toIntOrNull()?.let { it > 0 } == true && publicApiHash.matches(Regex("[0-9a-fA-F]{32}"))

// Half or malformed public keys would quietly build an app that asks every user for keys.
val validatePublicApiKeys = tasks.register("validatePublicApiKeys") {
    val given = publicKeysGiven
    val valid = publicApiKeysValid
    doLast {
        if (given && !valid) {
            throw GradleException(
                "COFFER_PUBLIC_API_ID must be a positive integer and COFFER_PUBLIC_API_HASH 32 hex characters " +
                    "in app/public-api-keys.properties (or leave both out to have users enter their own).",
            )
        }
    }
}

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
        val (bundled, id, hash) = when {
            personal -> Triple(apiIdValid && apiHashValid, apiId, apiHash)
            else -> Triple(publicApiKeysValid, publicApiId, publicApiHash)
        }
        variant.buildConfigFields?.apply {
            put("HAS_BUNDLED_API_KEYS", com.android.build.api.variant.BuildConfigField("boolean", bundled.toString(), null))
            put("API_ID", com.android.build.api.variant.BuildConfigField("int", if (bundled) id else "0", null))
            put("API_HASH", com.android.build.api.variant.BuildConfigField("String", if (bundled) "\"$hash\"" else "\"\"", null))
            // The public build lets a user bring keys of their own, with built-in ones or without.
            put("ALLOWS_OWN_API_KEYS", com.android.build.api.variant.BuildConfigField("boolean", (!personal).toString(), null))
        }
        val preBuild = "pre${variant.name.replaceFirstChar(Char::titlecase)}Build"
        tasks.named { it == preBuild }.configureEach { dependsOn(if (personal) validatePersonalApiKeys else validatePublicApiKeys) }
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:tdlib"))
    implementation(project(":core:vault"))
    implementation(project(":core:device"))
    implementation(project(":core:database"))
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
