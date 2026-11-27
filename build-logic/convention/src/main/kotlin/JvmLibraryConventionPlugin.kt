import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Pure Kotlin/JVM library. Code here also runs on Android, so it must only use
 * APIs available on [RozanBuild.MIN_SDK]; Android Lint checks that from the app
 * modules (`checkDependencies`).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureJvm(explicitApi = true)
        }
    }
}

internal fun Project.configureJvm(explicitApi: Boolean) {
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("com.android.lint")
    pluginManager.apply("rozan.quality")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(RozanBuild.TOOLCHAIN_JDK))
        sourceCompatibility = RozanBuild.JAVA_TARGET
        targetCompatibility = RozanBuild.JAVA_TARGET
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(RozanBuild.TOOLCHAIN_JDK)
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            applyRozanDefaults(explicitApi, name)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    extensions.configure<Lint> {
        warningsAsErrors = true
        abortOnError = true
        checkTestSources = true
        lintConfig = rootProject.file("config/lint.xml")
    }

    dependencies {
        "testImplementation"(platform(libs.library("junit-bom")))
        "testImplementation"(libs.library("junit-jupiter"))
        "testImplementation"(kotlin("test-junit5"))
        "testRuntimeOnly"(libs.library("junit-platform-launcher"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

private fun kotlin(module: String): String = "org.jetbrains.kotlin:kotlin-$module"
