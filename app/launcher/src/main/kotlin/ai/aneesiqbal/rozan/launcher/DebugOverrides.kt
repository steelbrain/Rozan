package ai.aneesiqbal.rozan.launcher

/**
 * Values that only debug and benchmark builds set, from adb, so that
 * measurements and screenshots show a known backdrop (`src/debug`:
 * `DebugOverridesProvider` reads them at process start, `DebugEventsReceiver`
 * changes them). Release builds contain no code that writes them, so there
 * they stay null and the defaults apply.
 */
internal object DebugOverrides {
    /** A backdrop file name (as in the manifest) to show instead of the shuffled ones. */
    @Volatile
    var backdropFile: String? = null

    /**
     * The release metadata URL to check for updates instead of GitHub's, such as a local test server's
     * `http://127.0.0.1:PORT/latest.json` (update.UrlPolicy; only debug builds allow that cleartext).
     */
    @Volatile
    var updateMetadataUrl: String? = null
}
