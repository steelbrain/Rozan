package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A vertical list scroller for D-pad settings screens: when focus moves, it
 * scrolls so the focused row keeps some room above and below it, instead of
 * stopping with the row flush against the screen edge. When rows above the
 * focused one appear, grow or disappear (a status loaded after the screen
 * opened), it scrolls again so the focused row stays on screen.
 */
public class SettingsScrollView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ScrollView(context, attrs) {
        private val margin = resources.getDimensionPixelSize(R.dimen.rozan_scroll_focus_margin)
        private val padded = Rect()
        private val focusedRect = Rect()
        private var contentHeight = -1

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            super.onLayout(changed, l, t, r, b)
            val height = getChildAt(0)?.height ?: 0
            val contentChanged = contentHeight >= 0 && height != contentHeight
            contentHeight = height
            if (!contentChanged) return
            val focused = findFocus() ?: return
            if (focused === this) return
            focused.getDrawingRect(focusedRect)
            offsetDescendantRectToMyCoords(focused, focusedRect)
            val delta = computeScrollDeltaToGetChildRectOnScreen(focusedRect)
            if (delta != 0) scrollBy(0, delta)
        }

        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            padded.set(rect)
            padded.inset(0, -margin)
            return super.computeScrollDeltaToGetChildRectOnScreen(padded)
        }
    }
