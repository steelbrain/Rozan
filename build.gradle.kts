plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.baselineprofile) apply false
    // validateTranslations, which check runs; see docs/DEVELOPMENT.md.
    id("rozan.translations")
}
