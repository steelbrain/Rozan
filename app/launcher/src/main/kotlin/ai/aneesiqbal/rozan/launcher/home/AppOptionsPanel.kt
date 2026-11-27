package ai.aneesiqbal.rozan.launcher.home

import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.TileView
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import kotlin.math.roundToInt
import ai.aneesiqbal.rozan.ui.R as UiR

/**
 * The small panel opened by long-pressing OK (or Menu) on an app tile: "App
 * info" and, for removable apps, "Uninstall". Focus stays inside it until it
 * closes; Back closes it.
 */
class AppOptionsPanel
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        /** What the panel's rows do. */
        interface Listener {
            fun onAppInfo(app: AppEntry)

            fun onUninstall(app: AppEntry)
        }

        var listener: Listener? = null

        private val titleView: TextView
        private val infoRow: SettingsRowView
        private val uninstallRow: SettingsRowView
        private val gap = resources.getDimensionPixelSize(R.dimen.options_panel_gap)
        private val location = IntArray(2)
        private var app: AppEntry? = null
        private var anchor: TileView? = null

        val isShowing: Boolean get() = isVisible

        init {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.options_panel_background)
            elevation = resources.getDimension(R.dimen.options_panel_elevation)
            val padding = resources.getDimensionPixelSize(R.dimen.options_panel_padding)
            setPadding(padding, padding, padding, padding)
            visibility = GONE

            titleView =
                TextView(context).apply {
                    setTextAppearance(UiR.style.TextAppearance_Rozan_SectionHeader)
                    maxLines = 1
                    val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                    setPaddingRelative(side, padding / 2, side, padding)
                }
            addView(titleView)
            infoRow = addRow(R.string.options_app_info) { listener?.onAppInfo(it) }
            uninstallRow = addRow(R.string.options_uninstall) { listener?.onUninstall(it) }
        }

        private fun addRow(
            title: Int,
            action: (AppEntry) -> Unit,
        ): SettingsRowView {
            val row = SettingsRowView(context)
            row.title = context.getString(title)
            row.setOnClickListener {
                val current = app ?: return@setOnClickListener
                hide()
                action(current)
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return row
        }

        /** Opens the panel for [app] next to [tile]. */
        fun show(
            tile: TileView,
            app: AppEntry,
        ) {
            this.app = app
            anchor = tile
            titleView.text = app.label
            uninstallRow.visibility = if (app.removable) VISIBLE else GONE
            val parentView = parent as View
            measure(
                MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(parentView.height, MeasureSpec.AT_MOST),
            )
            tile.getLocationInWindow(location)
            val tileLeft = location[0]
            val tileTop = location[1]
            val tileWidth = (tile.width * tile.scaleX).roundToInt()
            val tileHeight = (tile.height * tile.scaleY).roundToInt()
            parentView.getLocationInWindow(location)
            val safe = resources.getDimensionPixelSize(UiR.dimen.rozan_safe_horizontal)
            val left = (tileLeft - location[0]).coerceIn(safe, parentView.width - safe - measuredWidth)
            val below = tileTop - location[1] + tileHeight + gap
            val top =
                if (below + measuredHeight <=
                    parentView.height
                ) {
                    below
                } else {
                    tileTop - location[1] - gap - measuredHeight
                }
            translationX = left.toFloat()
            translationY = top.coerceAtLeast(0).toFloat()
            visibility = VISIBLE
            infoRow.requestFocus()
        }

        /** Closes the panel and gives focus back to its tile. */
        fun hide() {
            if (!isShowing) return
            visibility = GONE
            anchor?.requestFocus()
            anchor = null
            app = null
        }

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? {
            val next = super.focusSearch(focused, direction)
            // Keep focus inside the panel while it is open.
            return if (next != null && isDescendant(next)) next else focused
        }

        private fun isDescendant(view: View): Boolean {
            var current: Any? = view.parent
            while (current is View) {
                if (current === this) return true
                current = current.parent
            }
            return false
        }
    }
