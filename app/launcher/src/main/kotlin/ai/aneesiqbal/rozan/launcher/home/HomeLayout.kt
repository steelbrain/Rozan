package ai.aneesiqbal.rozan.launcher.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Root of the home screen. Lets the activity route the few focus moves that
 * cross between the grid and the top bar, where the platform's geometric
 * search would ignore the grid's translation.
 */
class HomeLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        /** Picks the next focus target, or returns null to use the default search. */
        fun interface FocusRouter {
            fun route(
                focused: View?,
                direction: Int,
            ): View?
        }

        var focusRouter: FocusRouter? = null

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? = focusRouter?.route(focused, direction) ?: super.focusSearch(focused, direction)
    }
