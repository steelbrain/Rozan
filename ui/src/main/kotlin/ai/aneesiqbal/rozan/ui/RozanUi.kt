package ai.aneesiqbal.rozan.ui

import android.animation.ValueAnimator

/** Shared TV UI building blocks. */
public object RozanUi {
    /**
     * False when the user turned animations off (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`).
     * Callers then jump straight to the end state instead of animating.
     */
    public fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
}
