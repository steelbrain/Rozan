package ai.aneesiqbal.rozan.launcher.settings

import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.ui.SettingsRowView
import ai.aneesiqbal.rozan.ui.SettingsWarningView
import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.text.toSpannable
import ai.aneesiqbal.rozan.ui.R as UiR

/**
 * Adds the parts of a settings screen to its vertical [list]: section headers, notes, shell commands,
 * plain rows, toggles and choices. Every launcher settings screen builds its list with one.
 */
internal class SettingsList(
    private val list: LinearLayout,
) {
    private val context: Context = list.context

    /** A section header. */
    fun header(
        @StringRes text: Int,
    ): TextView {
        val view =
            TextView(context).apply {
                setTextAppearance(UiR.style.TextAppearance_Rozan_SectionHeader)
                setText(text)
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, resources.getDimensionPixelSize(R.dimen.settings_header_top), side, 0)
            }
        add(view)
        return view
    }

    /** Secondary text below a row, at [index] in the list (the end by default). */
    fun note(
        @StringRes text: Int,
        index: Int = -1,
    ): TextView {
        val view =
            TextView(context).apply {
                setText(text)
                setTextColor(context.getColor(UiR.color.rozan_text_secondary))
                textSize = NOTE_TEXT_SP
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, 0, side, resources.getDimensionPixelSize(R.dimen.settings_note_bottom))
            }
        add(view, index)
        return view
    }

    /**
     * Shell commands, one per line, shown verbatim in a monospace font. A command that wraps
     * continues on indented lines, so each new command is the one that starts at the margin.
     */
    fun codeNote(text: String): TextView {
        val view =
            TextView(context).apply {
                typeface = Typeface.MONOSPACE
                // Small enough that the longest unbreakable word (LIST:<service>) fits on one line.
                textSize = CODE_TEXT_SP
                val indent = paint.measureText(CODE_CONTINUATION_INDENT).toInt()
                this.text =
                    text.toSpannable().apply {
                        setSpan(LeadingMarginSpan.Standard(0, indent), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                setTextColor(context.getColor(UiR.color.rozan_text_primary))
                val side = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
                setPaddingRelative(side, 0, side, resources.getDimensionPixelSize(R.dimen.settings_note_bottom))
            }
        add(view)
        return view
    }

    /** A focusable row titled [title], at [index] in the list (the end by default). */
    fun row(
        @StringRes title: Int,
        index: Int = -1,
    ): SettingsRowView = row(context.getString(title), index)

    /** A focusable row titled [title], at [index] in the list (the end by default). */
    fun row(
        title: CharSequence,
        index: Int = -1,
    ): SettingsRowView {
        val row = SettingsRowView(context)
        row.title = title
        add(row, index)
        return row
    }

    /** A focusable warning ([SettingsWarningView]) titled [title] and explained by [summary]. */
    fun warning(
        @StringRes title: Int,
        @StringRes summary: Int,
    ): SettingsWarningView {
        val view = SettingsWarningView(context)
        view.title = context.getString(title)
        view.summary = context.getString(summary)
        // Apart from the row under it, whose focused fill would otherwise touch the tinted background.
        val params =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = context.resources.getDimensionPixelSize(UiR.dimen.rozan_warning_margin_bottom)
        list.addView(view, params)
        return view
    }

    /** An on/off row: OK writes the opposite of what [read] returns. */
    fun toggle(
        @StringRes title: Int,
        @StringRes summary: Int?,
        read: () -> Boolean,
        write: (Boolean) -> Unit,
    ): SettingsRowView {
        val row = row(title)
        if (summary != null) row.summary = context.getString(summary)
        row.checked = read()
        row.setOnClickListener {
            write(!read())
            row.checked = read()
        }
        return row
    }

    /** A row of minutes from [choices]: OK steps forward, left and right step either way. */
    fun choice(
        @StringRes title: Int,
        choices: List<Int>,
        read: () -> Int,
        write: (Int) -> Unit,
    ): SettingsRowView {
        val row = row(title)

        fun show() {
            row.value = context.resources.getQuantityString(R.plurals.minutes, read(), read())
        }

        fun step(delta: Int) {
            val index = choices.indexOf(read()).coerceAtLeast(0)
            write(choices[(index + delta + choices.size) % choices.size])
            show()
        }
        show()
        row.setOnClickListener { step(1) }
        row.adjustListener = SettingsRowView.AdjustListener { step(it) }
        return row
    }

    /** Adds [view] at [index] (the end by default), as wide as the list. */
    fun add(
        view: View,
        index: Int = -1,
    ) {
        list.addView(
            view,
            index,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    /** The view just above [view] in the list. */
    fun before(view: View): View = list.getChildAt(list.indexOfChild(view) - 1)

    /** Removes [view] from the list. */
    fun remove(view: View) = list.removeView(view)

    private companion object {
        const val NOTE_TEXT_SP = 14f
        const val CODE_TEXT_SP = 13f
        const val CODE_CONTINUATION_INDENT = "  "
    }
}
