import org.gradle.api.Plugin
import org.gradle.api.Project

/** Desktop JVM tool (not shipped to devices). */
class JvmApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureJvm(explicitApi = false)
            pluginManager.apply("application")
        }
    }
}
