package ai.aneesiqbal.rozan.ui

import android.view.View

/**
 * Scales a view up while it has focus, animating only `scaleX`/`scaleY`.
 * Install with [attach]; it replaces the view's focus change listener.
 */
public class FocusScale private constructor(
    private val view: View,
    private val focusedScale: Float,
) : View.OnFocusChangeListener,
    FloatAnimator.Listener {
    private val animator = FloatAnimator(0f, DURATION_MS, this)

    override fun onFocusChange(
        v: View,
        hasFocus: Boolean,
    ) {
        animator.animateTo(if (hasFocus) 1f else 0f)
    }

    override fun onValue(value: Float) {
        val scale = 1f + (focusedScale - 1f) * value
        view.scaleX = scale
        view.scaleY = scale
    }

    /** Factory. */
    public companion object {
        private const val DURATION_MS = 180L

        /** Makes [view] grow to [focusedScale] while focused. */
        public fun attach(
            view: View,
            focusedScale: Float,
        ): FocusScale = FocusScale(view, focusedScale).also { view.onFocusChangeListener = it }
    }
}
