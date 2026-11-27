import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android application module.
 *
 * Release signing reads `ROZAN_KEYSTORE_FILE`, `ROZAN_KEYSTORE_PASSWORD`,
 * `ROZAN_KEY_ALIAS` and `ROZAN_KEY_PASSWORD` from the environment. Without them
 * a local release build is signed with the debug key, unless
 * `ROZAN_REQUIRE_RELEASE_SIGNING=true` (set by the release workflow), in which
 * case the build fails.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("rozan.quality")

            val version = RozanVersion.parse(providers.gradleProperty("rozan.version").get())
            val env = { name: String -> providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } }
            val keystore = env("ROZAN_KEYSTORE_FILE")
            val requireSigning = env("ROZAN_REQUIRE_RELEASE_SIGNING") == "true"
            if (requireSigning && keystore == null) {
                throw GradleException("ROZAN_REQUIRE_RELEASE_SIGNING is set but ROZAN_KEYSTORE_FILE is missing")
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = RozanBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = RozanBuild.MIN_SDK
                    targetSdk = RozanBuild.TARGET_SDK
                    versionCode = version.code
                    versionName = version.name
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = RozanBuild.JAVA_TARGET
                    targetCompatibility = RozanBuild.JAVA_TARGET
                }
                signingConfigs {
                    if (keystore != null) {
                        create("release") {
                            storeFile = rootProject.file(keystore)
                            storePassword = env("ROZAN_KEYSTORE_PASSWORD")
                            keyAlias = env("ROZAN_KEY_ALIAS")
                            keyPassword = env("ROZAN_KEY_PASSWORD")
                            enableV1Signing = false
                            enableV2Signing = true
                            enableV3Signing = true
                        }
                    }
                }
                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                        signingConfig =
                            if (keystore != null) {
                                signingConfigs.getByName("release")
                            } else {
                                signingConfigs.getByName("debug")
                            }
                    }
                }
                packaging {
                    resources {
                        excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
                    }
                }
                dependenciesInfo {
                    includeInApk = false
                    includeInBundle = false
                }
                // The language can be chosen in the app (AppLanguage), so every install carries every
                // language: an app bundle must not split them out. The sideloaded APKs always hold all.
                bundle {
                    language {
                        enableSplit = false
                    }
                }
                lint {
                    configureRozanLint(project, checkDependencies = true)
                }
            }
            configureAndroidKotlin(explicitApi = false)
        }
    }
}

/** `major.minor.patch` → versionCode `major * 1_000_000 + minor * 1_000 + patch`. */
internal data class RozanVersion(val name: String, val code: Int) {
    companion object {
        private const val MAJOR = 1_000_000
        private const val MINOR = 1_000

        fun parse(name: String): RozanVersion {
            val parts = name.substringBefore('-').split('.').map { it.toInt() }
            require(parts.size == 3 && parts.all { it in 0 until MINOR }) { "rozan.version must be major.minor.patch, got $name" }
            return RozanVersion(name, parts[0] * MAJOR + parts[1] * MINOR + parts[2])
        }
    }
}
