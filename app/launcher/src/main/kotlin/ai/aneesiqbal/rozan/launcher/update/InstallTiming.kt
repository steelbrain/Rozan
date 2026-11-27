package ai.aneesiqbal.rozan.launcher.update

/**
 * When a downloaded and verified update may be installed. Installing kills
 * every Rozan process (the home screen, the AirPlay receiver and the Home
 * button redirect), so an install the user did not ask for waits until no
 * Rozan screen is showing and no sender uses AirPlay. Android 11 and earlier
 * (API 28 to 30: Fire OS 7 and 8) always ask the user to confirm an update,
 * and so does every version while the app may not install apps; Rozan never
 * shows that confirmation from the background, only when the user selects
 * "Install update" in its settings.
 */
internal object InstallTiming {
    /** What to do with a verified update now. */
    enum class Decision {
        /** Install now; Android may still ask the user (handled by the installer). */
        INSTALL,

        /** Keep the file; the settings offer "Install update" and the user confirms there. */
        ASK_USER,

        /** Wait until no Rozan screen is showing. */
        WAIT_FOR_SCREEN,

        /** Wait until AirPlay is no longer in use. */
        WAIT_FOR_AIRPLAY,
    }

    /** First API level where an app may update itself without the user's confirmation (Android 12). */
    const val SILENT_INSTALL_API = 31

    /**
     * Decides for one moment.
     *
     * @param sdkInt the device's API level.
     * @param userInitiated the user selected "Install update" just now.
     * @param mayInstallApps the "install unknown apps" app op is allowed for Rozan.
     * @param rozanOnScreen a Rozan activity is started (visible) in the UI process.
     * @param airPlayInUse the receiver shows a code or a sender mirrors or plays audio.
     */
    fun decide(
        sdkInt: Int,
        userInitiated: Boolean,
        mayInstallApps: Boolean,
        rozanOnScreen: Boolean,
        airPlayInUse: Boolean,
    ): Decision =
        when {
            userInitiated -> Decision.INSTALL
            sdkInt < SILENT_INSTALL_API || !mayInstallApps -> Decision.ASK_USER
            airPlayInUse -> Decision.WAIT_FOR_AIRPLAY
            rozanOnScreen -> Decision.WAIT_FOR_SCREEN
            else -> Decision.INSTALL
        }
}
