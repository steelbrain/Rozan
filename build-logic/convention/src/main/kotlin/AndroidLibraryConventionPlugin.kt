import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library module (AAR). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("rozan.quality")

            extensions.configure<LibraryExtension> {
                compileSdk = RozanBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = RozanBuild.MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }
                compileOptions {
                    sourceCompatibility = RozanBuild.JAVA_TARGET
                    targetCompatibility = RozanBuild.JAVA_TARGET
                }
                lint {
                    configureRozanLint(project, checkDependencies = false)
                }
                testOptions {
                    unitTests.isReturnDefaultValues = false
                }
            }
            configureAndroidKotlin(explicitApi = true)
        }
    }
}
