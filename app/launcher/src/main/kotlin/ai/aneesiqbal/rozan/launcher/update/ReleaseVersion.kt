package ai.aneesiqbal.rozan.launcher.update

/**
 * A release version `major.minor.patch`, each part 0 to 999, as in
 * `rozan.version` (build-logic `RozanVersion`). Pre-release suffixes such as
 * `-rc.1` are not release versions here: the updater installs releases only.
 */
internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ReleaseVersion> {
    init {
        require(major in PARTS && minor in PARTS && patch in PARTS) { "version part out of range" }
    }

    /** The Android `versionCode` of this version: `major * 1_000_000 + minor * 1_000 + patch`. */
    val code: Long get() = major * MAJOR_STEP + minor * MINOR_STEP + patch

    /** `major.minor.patch`. */
    val name: String get() = "$major.$minor.$patch"

    override fun compareTo(other: ReleaseVersion): Int = code.compareTo(other.code)

    override fun toString(): String = name

    companion object {
        private val PARTS = 0..999
        private const val MAJOR_STEP = 1_000_000L
        private const val MINOR_STEP = 1_000L
        private const val MAX_PART_DIGITS = 3
        private const val PART_COUNT = 3

        /** Longest tag looked at; `v999.999.999` is 12 chars. */
        const val MAX_TAG_LENGTH = 32

        /** Parses `major.minor.patch` (digits only, 1 to 3 per part), or returns null. */
        fun parse(text: String): ReleaseVersion? {
            if (text.length > MAX_TAG_LENGTH) return null
            val parts = text.split('.')
            if (parts.size != PART_COUNT) return null
            val numbers =
                parts.map { part ->
                    if (part.isEmpty() || part.length > MAX_PART_DIGITS || !part.all { it in '0'..'9' }) return null
                    part.toInt()
                }
            return ReleaseVersion(numbers[0], numbers[1], numbers[2])
        }

        /** Parses a release tag `vX.Y.Z`, or returns null (including for pre-release tags). */
        fun parseTag(tag: String): ReleaseVersion? = if (tag.startsWith("v")) parse(tag.substring(1)) else null
    }
}
