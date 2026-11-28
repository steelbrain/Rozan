import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Checks the app's translations against the rules in [TranslationValidator] and fails with every
 * violation, each naming the module, language, string and what is wrong. The report also goes to
 * [reportFile].
 */
@CacheableTask
abstract class ValidateTranslationsTask : DefaultTask() {
    /** Gradle path of each module → its `src/main/res`, relative to [rootDirectory]. */
    @get:Input
    abstract val resourceDirectories: MapProperty<String, String>

    /** The `values*` XML files of those directories: what the check reads from them. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val resourceFiles: ConfigurableFileCollection

    /** `config/translations.xml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    /** The launcher's `locales_config.xml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localeConfigFile: RegularFileProperty

    /** The Kotlin source that declares [languageEnum]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val languageEnumFile: RegularFileProperty

    /** Simple name of the enum of app languages, whose entries start with their BCP 47 tag. */
    @get:Input
    abstract val languageEnum: Property<String>

    /** The repository root; paths in the report are relative to it. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** The violations found, one per line, or a line saying there are none. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Runs the check. */
    @TaskAction
    fun validate() {
        val root = rootDirectory.get().asFile
        val violations =
            try {
                val rules = TranslationRules.parse(rulesFile.get().asFile)
                val modules = TranslationSources.load(root, resourceDirectories.get())
                val localeConfig = localeConfigFile.get().asFile
                val enumSource = languageEnumFile.get().asFile
                val languages =
                    LanguageLists(
                        LanguageLists.parseLocaleConfig(localeConfig),
                        relative(root, localeConfig),
                        LanguageLists.parseEnumTags(enumSource.readText(), languageEnum.get()),
                        relative(root, enumSource),
                    )
                TranslationValidator(rules).validate(modules, languages)
            } catch (e: IllegalArgumentException) {
                throw GradleException("Translation check: ${e.message}", e)
            }
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        if (violations.isEmpty()) {
            report.writeText("No translation problems.\n")
            return
        }
        val text = violations.joinToString("\n", postfix = "\n")
        report.writeText(text)
        throw GradleException(
            "${violations.size} translation problem(s). Each line is rule: module language string: problem (file). " +
                "The rules, protected terms and allowed English copies are in ${TranslationValidator.CONFIG_FILE}; " +
                "docs/DEVELOPMENT.md explains them.\n" + text,
        )
    }

    private fun relative(
        root: File,
        file: File,
    ): String = file.relativeTo(root).invariantSeparatorsPath
}
