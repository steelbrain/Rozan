package ai.aneesiqbal.rozan.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator

/**
 * Moves a float towards a target with one reused [ValueAnimator].
 *
 * Retargeting mid-flight continues from the current value. Neither [animateTo]
 * nor the per-frame update allocates, so this can run from focus and key
 * handlers. When animations are turned off system-wide the value jumps to the
 * target immediately. An animation that runs to its end always leaves exactly
 * the target as the last value (the interpolated last frame can be a rounding
 * error away), so listeners may compare against it.
 */
public class FloatAnimator(
    initialValue: Float,
    durationMs: Long,
    private val listener: Listener,
    interpolator: TimeInterpolator = EASE_OUT,
) : ValueAnimator.AnimatorUpdateListener {
    /** Receives every new value, on the main thread. */
    public fun interface Listener {
        /** Called with the current value; [value] is a primitive, so no boxing happens per frame. */
        public fun onValue(value: Float)
    }

    private val animator: ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).also {
            it.duration = durationMs
            it.interpolator = interpolator
            it.addUpdateListener(this)
        }
    private var from = initialValue

    /** The value most recently passed to the listener. */
    public var value: Float = initialValue
        private set

    /** The value the animation is heading to (equal to [value] when idle). */
    public var target: Float = initialValue
        private set

    /** Whether an animation is in progress. */
    public val isRunning: Boolean get() = animator.isRunning

    /** Animates from the current value to [newTarget]. */
    public fun animateTo(newTarget: Float) {
        if (newTarget == target && (animator.isRunning || value == newTarget)) return
        animator.cancel()
        target = newTarget
        if (!RozanUi.animationsEnabled()) {
            publish(newTarget)
            return
        }
        from = value
        animator.start()
    }

    /** Sets the value to [newTarget] without animating. */
    public fun jumpTo(newTarget: Float) {
        animator.cancel()
        target = newTarget
        publish(newTarget)
    }

    /** Animates when [animate] is true and animations are enabled, otherwise jumps. */
    public fun moveTo(
        newTarget: Float,
        animate: Boolean,
    ) {
        if (animate) animateTo(newTarget) else jumpTo(newTarget)
    }

    override fun onAnimationUpdate(animation: ValueAnimator) {
        // The last frame (and end()) has fraction 1: publish the target itself, not the product.
        val fraction = animation.animatedFraction
        publish(if (fraction == 1f) target else from + (target - from) * fraction)
    }

    private fun publish(newValue: Float) {
        value = newValue
        listener.onValue(newValue)
    }

    private companion object {
        /** Quick start, long gentle settle: the movement reads as a response, not a transition. */
        val EASE_OUT: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
