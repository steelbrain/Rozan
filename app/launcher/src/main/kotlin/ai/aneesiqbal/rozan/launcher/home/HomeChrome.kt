package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.backdrop.BackdropController
import ai.aneesiqbal.rozan.ui.FloatAnimator
import ai.aneesiqbal.rozan.ui.TileGridView
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Everything on the home screen that moves as a whole: the grid's vertical
 * position, the scrims, and the fade into the screensaver. Only RenderNode
 * properties (`translationY`, `alpha`) change, so none of it re-lays out or
 * re-records a display list.
 *
 * - First row focused: the grid sits low, the backdrop shows through a
 *   bottom-heavy scrim ([restScrim]).
 * - Any lower row: the grid slides up so the focused row sits at a fixed line,
 *   and [gridScrim] dims the whole backdrop. Once that has settled, the
 *   [backdrop] draws itself dimmed instead (see [applyAlpha]).
 * - Screensaver: grid, scrims and the top bar's buttons fade out; the clock
 *   (with its own small top scrim) stays and moves into the corner. [onShown]
 *   runs once that fade has finished.
 */
class HomeChrome(
    private val grid: TileGridView,
    private val restScrim: View,
    private val gridScrim: View,
    /** The top bar's buttons (Inputs, when shown, and the gear), at the end of the bar. */
    private val buttons: View,
    private val clock: View,
    private val backdrop: BackdropController,
    /** Runs when the screensaver has fully faded in (at once with animations off). */
    private val onShown: Runnable,
) {
    /** The grid scrim's colour, for the backdrop to apply itself; 0 if it is not a plain colour. */
    private val scrimColor = (gridScrim.background as? ColorDrawable)?.color ?: 0

    /** Whether the backdrop now draws itself darkened in place of [gridScrim]. */
    var backdropTinted = false
        private set

    /** False keeps [gridScrim] a view at rest too, as before the backdrop could darken itself (tests). */
    internal var tintsBackdrop = true
        set(value) {
            field = value
            applyAlpha()
        }

    private var gridMode = 0f
    private var saver = 0f
    private var gridFadesAsOne = false
    private val translation = FloatAnimator(0f, SCROLL_MS, { grid.translationY = it })
    private val gridModeAnimator =
        FloatAnimator(0f, SCROLL_MS, {
            gridMode = it
            applyAlpha()
        })
    private val saverAnimator =
        FloatAnimator(0f, SAVER_FADE_MS, {
            val before = saver
            saver = it
            applyAlpha()
            if (it == 1f && before < 1f) onShown.run()
        }, LinearInterpolator())

    init {
        // The Inputs button comes and goes with the TV's inputs, which moves the clock in the bar.
        buttons.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeClock() }
    }

    /** Scrolls so that [row] is in view (row 0: grid at rest, backdrop visible). */
    fun showRow(
        row: Int,
        animate: Boolean,
    ) {
        val viewport = (grid.parent as View).height
        val target = if (row == 0) 0f else viewport * FOCUS_LINE - grid.rowTop(row)
        translation.moveTo(target, animate)
        gridModeAnimator.moveTo(if (row == 0) 0f else 1f, animate)
    }

    /** Fades the home screen into or out of the screensaver. */
    fun setScreensaver(
        active: Boolean,
        animate: Boolean,
    ) {
        saverAnimator.moveTo(if (active) 1f else 0f, animate)
    }

    /** The backdrop's coverage changed (a photo arrived or was released). */
    fun onBackdropCoverageChanged() {
        applyAlpha()
    }

    /**
     * Scrims, grid and top bar for the current mode and fade. While a lower row has focus and nothing
     * fades, the backdrop draws itself with the scrim's colour on top and [gridScrim] is hidden: the same
     * pixels without blending a second full-screen layer on every frame. While the scrim fades it is
     * drawn as a view again, with its alpha (a colour filter would need its display list re-recorded on
     * every frame); the switch happens within one frame.
     */
    private fun applyAlpha() {
        val visible = 1f - saver
        val gridDim = gridMode * visible
        val tinted = tintsBackdrop && gridDim == 1f && scrimColor != 0 && backdrop.isCovered
        backdropTinted = tinted
        backdrop.setTint(if (tinted) scrimColor else 0)
        restScrim.alpha = (1f - gridMode) * visible
        gridScrim.alpha = if (tinted) 0f else gridDim
        grid.alpha = visible
        setGridFadesAsOne(saver > 0f && saver < 1f)
        // The buttons are gone by the middle of the fade, before the clock
        // slides over to the screen's safe margin where the gear was.
        buttons.alpha = (1f - saver * BUTTON_FADE_SPEED).coerceAtLeast(0f)
        placeClock()
    }

    /** Moves the clock towards the end of the bar as the screensaver fades in. */
    private fun placeClock() {
        clock.translationX = (buttons.right - clock.right) * saver
    }

    /**
     * Faded tile by tile (the grid normally has no overlapping rendering, so no
     * offscreen layer), the focused tile's shadow shows through its own
     * translucent body. While the screensaver fade runs, the grid is drawn into
     * one layer and faded as a whole instead; at rest it costs nothing extra.
     */
    private fun setGridFadesAsOne(fadesAsOne: Boolean) {
        if (fadesAsOne == gridFadesAsOne) return
        gridFadesAsOne = fadesAsOne
        grid.forceHasOverlappingRendering(fadesAsOne)
        // The flag reaches the RenderNode when the grid's display list is re-recorded.
        grid.invalidate()
    }

    private companion object {
        const val SCROLL_MS = 320L
        const val SAVER_FADE_MS = 900L

        /** The buttons fade this many times faster than the rest. */
        const val BUTTON_FADE_SPEED = 2f

        /** Where the focused row's top sits when scrolled, as a fraction of the screen height. */
        const val FOCUS_LINE = 0.36f
    }
}
