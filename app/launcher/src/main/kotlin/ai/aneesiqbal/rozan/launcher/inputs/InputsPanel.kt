package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.SettingsScrollView
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import ai.aneesiqbal.rozan.ui.R as UiR

/**
 * The inputs menu, opened from the Inputs button in the top-right corner: one
 * row per input, and, when switching fails, a short message with a row that
 * opens the TV's own input list. It hangs below the button like the app panel
 * hangs below a tile. Focus stays inside it until it closes; Back closes it.
 */
class InputsPanel
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        /** What the panel's rows do. */
        internal interface Listener {
            fun onInputSelected(entry: InputEntry)

            fun onOpenSystemPicker()
        }

        internal var listener: Listener? = null

        /** Where focus goes when the panel closes. */
        var anchor: View? = null

        private val rows: LinearLayout
        private val message: TextView
        private val pickerRow: SettingsRowView

        val isShowing: Boolean get() = isVisible

        init {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.options_panel_background)
            elevation = resources.getDimension(R.dimen.options_panel_elevation)
            val padding = resources.getDimensionPixelSize(R.dimen.options_panel_padding)
            setPadding(padding, padding, padding, padding)
            visibility = GONE
            val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)

            val header =
                TextView(context).apply {
                    setTextAppearance(UiR.style.TextAppearance_Rozan_SectionHeader)
                    maxLines = 1
                    setPaddingRelative(side, padding / 2, side, padding)
                    setText(R.string.inputs_title)
                }
            addView(header)

            rows = LinearLayout(context).apply { orientation = VERTICAL }
            val scroll = SettingsScrollView(context)
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            // Weighted, so that with more inputs than fit on screen the list scrolls inside the panel.
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))

            message =
                TextView(context).apply {
                    setTextColor(context.getColor(UiR.color.rozan_text_secondary))
                    textSize = MESSAGE_TEXT_SP
                    setPaddingRelative(side, padding, side, padding / 2)
                    visibility = GONE
                }
            addView(message)
            pickerRow =
                SettingsRowView(context).apply {
                    title = context.getString(R.string.inputs_open_system)
                    visibility = GONE
                    setOnClickListener { listener?.onOpenSystemPicker() }
                }
            addView(pickerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        /** Opens the panel with [entries] and focuses the first one. */
        internal fun show(
            entries: List<InputEntry>,
            icons: Map<String, Drawable>,
        ) {
            clearMessage()
            setEntries(entries, icons)
            visibility = VISIBLE
            rows.getChildAt(0)?.requestFocus()
        }

        /**
         * Replaces the rows while the panel is open (an input was added, removed or
         * changed state). Focus stays on the same input if it is still listed.
         */
        internal fun update(
            entries: List<InputEntry>,
            icons: Map<String, Drawable>,
        ) {
            val focusedId = (rows.findFocus() as? InputRowView)?.entry?.id
            val hadFocus = rows.hasFocus()
            setEntries(entries, icons)
            if (!hadFocus) return
            val target = rowFor(focusedId) ?: rows.getChildAt(0)
            target?.requestFocus()
        }

        /** Switching to [entry] failed: says so, and offers the TV's input list if [canOpenPicker]. */
        internal fun showSwitchFailed(
            entry: InputEntry,
            canOpenPicker: Boolean,
        ) {
            message.text = context.getString(R.string.inputs_switch_failed, entry.title)
            message.visibility = VISIBLE
            pickerRow.isVisible = canOpenPicker
            if (canOpenPicker) pickerRow.requestFocus()
        }

        /** The TV's input list would not open either: says so and takes its row away. */
        internal fun showPickerFailed() {
            message.setText(R.string.inputs_picker_failed)
            message.visibility = VISIBLE
            // Move focus off the row before it goes, so that it stays inside the panel.
            if (pickerRow.hasFocus()) rows.getChildAt(0)?.requestFocus()
            pickerRow.visibility = GONE
        }

        /** Closes the panel and gives focus back to [anchor]. */
        fun hide() {
            if (!isShowing) return
            visibility = GONE
            clearMessage()
            anchor?.requestFocus()
        }

        /** The input rows in order (for tests). */
        internal fun inputRows(): List<InputRowView> =
            (0 until rows.childCount).map { rows.getChildAt(it) as InputRowView }

        /** The message after a failed switch, or null (for tests). */
        internal val messageText: CharSequence? get() = message.text.takeIf { message.isVisible }

        /** The row that opens the TV's input list, or null while it is not shown (for tests). */
        internal val pickerRowShown: CharSequence? get() = pickerRow.title.takeIf { pickerRow.isVisible }

        private fun setEntries(
            entries: List<InputEntry>,
            icons: Map<String, Drawable>,
        ) {
            // Reuse the rows there are; an input list is a handful of rows and changes rarely.
            while (rows.childCount > entries.size) rows.removeViewAt(rows.childCount - 1)
            while (rows.childCount < entries.size) {
                val row = InputRowView(context)
                row.setOnClickListener { view -> (view as InputRowView).entry?.let { listener?.onInputSelected(it) } }
                rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            entries.forEachIndexed {
                index,
                entry,
                ->
                (rows.getChildAt(index) as InputRowView).bind(entry, icons[entry.id])
            }
        }

        private fun rowFor(id: String?): View? {
            if (id == null) return null
            for (index in 0 until rows.childCount) {
                val row = rows.getChildAt(index) as InputRowView
                if (row.entry?.id == id) return row
            }
            return null
        }

        private fun clearMessage() {
            message.visibility = GONE
            pickerRow.visibility = GONE
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

        private companion object {
            const val MESSAGE_TEXT_SP = 16f
        }
    }
