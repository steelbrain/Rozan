package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.R
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** The fixed first row of the home screen, in display order. */
enum class BuiltIn(
    @param:StringRes val label: Int,
    @param:DrawableRes val glyph: Int,
    @param:ColorRes val tint: Int,
) {
    SETTINGS(R.string.tile_settings, R.drawable.glyph_settings, R.color.tile_settings),
    NETWORK(R.string.tile_network, R.drawable.glyph_network, R.color.tile_network),
    AIRPLAY(R.string.tile_airplay, R.drawable.glyph_airplay, R.color.tile_airplay),
    SCREENSAVER(R.string.tile_screensaver, R.drawable.glyph_screensaver, R.color.tile_screensaver),
}
