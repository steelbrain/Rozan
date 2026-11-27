import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/** Shared build constants. Changing these affects every module. */
internal object RozanBuild {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36

    /** Android 9 (API 28): Fire OS 7 and every Fire TV sold since 2018. */
    const val MIN_SDK = 28

    /** JDK used to run compilers and tests. */
    const val TOOLCHAIN_JDK = 21

    /** Bytecode level. Android's D8 supports Java 17 bytecode. */
    val JAVA_TARGET: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/**
 * Compiler flags shared by every Kotlin compilation in the project. Explicit API
 * mode applies to production code only, never to test compilations.
 */
internal fun KotlinJvmCompilerOptions.applyRozanDefaults(
    explicitApi: Boolean,
    taskName: String,
) {
    jvmTarget.set(RozanBuild.JVM_TARGET)
    allWarningsAsErrors.set(true)
    progressiveMode.set(true)
    freeCompilerArgs.add("-Xjsr305=strict")
    if (explicitApi && !taskName.contains("Test")) {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
