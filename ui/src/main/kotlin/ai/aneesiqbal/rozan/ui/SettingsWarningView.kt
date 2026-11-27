package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * A settings row that warns: a warning sign, the title in the warning colour
 * and an explanation under it, on a background tinted with that colour. It is
 * focusable and clickable like a [SettingsRowView], so selecting it can act on
 * the warning; focused, it fills with the warning colour and its text turns
 * dark. Screen readers hear "Warning" before the title.
 */
public class SettingsWarningView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val titleView: TextView
        private val summaryView: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = resources.getDimensionPixelSize(R.dimen.rozan_row_min_height)
            val horizontal = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_horizontal)
            val vertical = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_vertical)
            setPaddingRelative(horizontal, vertical, horizontal, vertical)
            setBackgroundResource(R.drawable.rozan_warning_background)
            LayoutInflater.from(context).inflate(R.layout.rozan_settings_warning, this, true)
            titleView = findViewById(R.id.rozan_warning_title)
            summaryView = findViewById(R.id.rozan_warning_summary)
        }

        /** What is wrong, in a few words. */
        public var title: CharSequence
            get() = titleView.text
            set(value) {
                titleView.text = value
                contentDescription = context.getString(R.string.rozan_warning) + ", " + value
            }

        /** Why it matters and what selecting the row does; null hides it. */
        public var summary: CharSequence?
            get() = summaryView.text.takeIf { summaryView.isVisible }
            set(value) {
                summaryView.text = value
                summaryView.isVisible = !value.isNullOrEmpty()
            }
    }
