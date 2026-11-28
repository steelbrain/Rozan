import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Root project: `validateTranslations` ([ValidateTranslationsTask]) over the string resources of
 * every module, run once by `./gradlew check`. That `check` also runs build-logic's own tests, which
 * cover the validator.
 */
class TranslationsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            require(this == rootProject) { "rozan.translations applies to the root project only" }
            pluginManager.apply(LifecycleBasePlugin::class.java)

            val resourceDirectories =
                subprojects.associate { project ->
                    val res = project.projectDir.resolve("src/main/res")
                    project.path to res.relativeTo(rootDir).invariantSeparatorsPath
                }
            val validate =
                tasks.register<ValidateTranslationsTask>("validateTranslations") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Checks the translations for what Android Lint does not (config/translations.xml)."
                    this.resourceDirectories.set(resourceDirectories)
                    resourceFiles.from(
                        layout.projectDirectory.asFileTree.matching {
                            resourceDirectories.values.forEach { include("$it/values*/*.xml") }
                        },
                    )
                    rulesFile.set(layout.projectDirectory.file("config/translations.xml"))
                    localeConfigFile.set(layout.projectDirectory.file(LOCALE_CONFIG))
                    languageEnumFile.set(layout.projectDirectory.file(LANGUAGE_ENUM_SOURCE))
                    languageEnum.set("Language")
                    rootDirectory.set(layout.projectDirectory)
                    reportFile.set(layout.buildDirectory.file("reports/translations/violations.txt"))
                }
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                dependsOn(validate)
                dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
            }
        }
    }

    private companion object {
        const val LOCALE_CONFIG = "app/launcher/src/main/res/xml/locales_config.xml"
        const val LANGUAGE_ENUM_SOURCE =
            "airplay/android/src/main/kotlin/ai/aneesiqbal/rozan/airplay/android/AppLanguage.kt"
    }
}
