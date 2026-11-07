import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal object CofferSdk {
    const val COMPILE = 37
    const val TARGET = 37

    // 30, not 29: BiometricPrompt with a CryptoObject accepts DEVICE_CREDENTIAL only from API 30 (SPEC §3.8).
    const val MIN = 30

    // 21: lazysodium 5.2.0 (the first with 16 KB aligned libraries) ships Java 21 bytecode.
    val JAVA = JavaVersion.VERSION_21
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in libs.versions.toml") }

internal val Project.androidExtension: CommonExtension
    get() = extensions.getByName("android") as CommonExtension

/** Namespace follows the module path, so `:core:crypto` becomes `me.esdex.coffer.core.crypto`. */
internal val Project.cofferNamespace: String
    get() = "me.esdex.coffer" + path.replace(':', '.')

internal fun configureAndroid(android: CommonExtension) {
    android.compileSdk = CofferSdk.COMPILE
    android.defaultConfig.minSdk = CofferSdk.MIN
    android.compileOptions.sourceCompatibility = CofferSdk.JAVA
    android.compileOptions.targetCompatibility = CofferSdk.JAVA
}
