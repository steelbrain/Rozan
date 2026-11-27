package ai.aneesiqbal.rozan.airplay.android.session

import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the physical display mode into the [DisplayInfo] reported to clients.
 *
 * Senders encode at the size we report. Anything above 1920×1080 costs
 * bandwidth and decoder headroom on TV SoCs for no visible gain at couch
 * distance, so larger modes (4K panels) are scaled down keeping the aspect
 * ratio. The refresh rate is reported as it is, rounded to whole hertz.
 */
internal object DisplayFit {
    const val MAX_LONG_SIDE = 1920
    const val MAX_SHORT_SIDE = 1080
    private const val DEFAULT_REFRESH_HZ = 60

    fun fit(
        widthPixels: Int,
        heightPixels: Int,
        refreshRateHz: Float,
    ): DisplayInfo {
        val valid = refreshRateHz.isFinite() && refreshRateHz >= 1f
        val refresh = if (valid) refreshRateHz.roundToInt() else DEFAULT_REFRESH_HZ
        if (widthPixels <= 0 || heightPixels <= 0) return DisplayInfo(MAX_LONG_SIDE, MAX_SHORT_SIDE, refresh)
        val landscape = widthPixels >= heightPixels
        val maxWidth = if (landscape) MAX_LONG_SIDE else MAX_SHORT_SIDE
        val maxHeight = if (landscape) MAX_SHORT_SIDE else MAX_LONG_SIDE
        val scale = min(1.0, min(maxWidth.toDouble() / widthPixels, maxHeight.toDouble() / heightPixels))
        val width = even((widthPixels * scale).roundToInt()).coerceIn(2, maxWidth)
        val height = even((heightPixels * scale).roundToInt()).coerceIn(2, maxHeight)
        return DisplayInfo(width, height, refresh)
    }

    // Video encoders work in 2×2 chroma blocks; odd sizes get padded and cropped anyway.
    private fun even(value: Int): Int = value and 1.inv()
}
