package ai.aneesiqbal.rozan.launcher.notices

import ai.aneesiqbal.rozan.launcher.R
import androidx.annotation.RawRes

/** A third-party library in the app, as the open-source notices list it. */
internal data class Library(
    /** The library's name; also the key the notice screens pass around. */
    val name: String,
    /** The artifacts an entry covers when it stands for several; null for a single library. */
    val parts: String?,
    /** Who holds the copyright. */
    val holder: String,
    /** The license's name. */
    val license: String,
    /** The full license text, with the holder's copyright line where the license has one. */
    @param:RawRes val text: Int,
)

/** The third-party software in the launcher APK: the runtime dependencies and what they bring with them. */
internal object OpenSourceNotices {
    private const val APACHE_2_0 = "Apache License 2.0"
    private const val MIT = "MIT License"

    val LIBRARIES: List<Library> =
        listOf(
            Library("Kotlin standard library", null, "JetBrains", APACHE_2_0, R.raw.license_apache_2_0),
            Library("kotlinx.coroutines", null, "JetBrains", APACHE_2_0, R.raw.license_apache_2_0),
            Library("JetBrains Java annotations", null, "JetBrains", APACHE_2_0, R.raw.license_apache_2_0),
            Library(
                "AndroidX",
                "annotation, arch-core, collection, concurrent-futures, core, core-ktx, core-viewtree, " +
                    "interpolator, lifecycle, profileinstaller, startup, tracing, versionedparcelable",
                "The Android Open Source Project",
                APACHE_2_0,
                R.raw.license_apache_2_0,
            ),
            Library("Guava ListenableFuture", null, "Google", APACHE_2_0, R.raw.license_apache_2_0),
            Library("JSpecify annotations", null, "The JSpecify authors", APACHE_2_0, R.raw.license_apache_2_0),
            Library(
                "Bouncy Castle Crypto APIs for Java",
                null,
                "The Legion of the Bouncy Castle Inc.",
                MIT,
                R.raw.license_mit_bouncy_castle,
            ),
        )

    /** The library named [name], if there is one. */
    fun named(name: String?): Library? = LIBRARIES.firstOrNull { it.name == name }
}
