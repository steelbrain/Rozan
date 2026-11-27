import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android test module (`com.android.test`): instrumentation that drives an app
 * module from its own APK, e.g. Macrobenchmark and Baseline Profile generators.
 * The module sets `targetProjectPath` itself.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            pluginManager.apply("rozan.quality")

            extensions.configure<TestExtension> {
                compileSdk = RozanBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = RozanBuild.MIN_SDK
                    targetSdk = RozanBuild.TARGET_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = RozanBuild.JAVA_TARGET
                    targetCompatibility = RozanBuild.JAVA_TARGET
                }
                lint {
                    configureRozanLint(project, checkDependencies = false)
                }
            }
            configureAndroidKotlin(explicitApi = false)
        }
    }
}
