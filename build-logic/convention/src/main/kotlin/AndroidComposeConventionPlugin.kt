import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Apply after the Android application or library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        androidExtension.buildFeatures.compose = true
        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            "implementation"(bom)
            "implementation"(libs.lib("androidx-compose-ui"))
            "implementation"(libs.lib("androidx-compose-ui-graphics"))
            "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
            "implementation"(libs.lib("androidx-compose-material3"))
            "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
        }
    }
}
