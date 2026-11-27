import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Formatting (ktlint through Spotless) and static analysis (detekt) for a module. */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("dev.detekt")

            val ktlintVersion = libs.version("ktlint")
            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    ktlint(ktlintVersion)
                }
                kotlinGradle {
                    target("*.gradle.kts")
                    ktlint(ktlintVersion)
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
                parallel.set(true)
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                basePath.set(rootProject.layout.projectDirectory)
            }
        }
    }
}
