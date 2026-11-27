import com.android.build.api.dsl.Lint
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal fun Lint.configureRozanLint(
    project: Project,
    checkDependencies: Boolean,
) {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    checkTestSources = true
    this.checkDependencies = checkDependencies
    lintConfig = project.rootProject.file("config/lint.xml")
}

/** AGP 9 compiles Kotlin itself (built-in Kotlin); configure its compile tasks. */
internal fun Project.configureAndroidKotlin(explicitApi: Boolean) {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            applyRozanDefaults(explicitApi, name)
        }
    }
}
