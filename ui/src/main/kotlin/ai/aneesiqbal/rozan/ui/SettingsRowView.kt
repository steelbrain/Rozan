package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * A D-pad friendly settings row: title, optional summary, and either a value
 * (for choices) or an on/off switch on the trailing side.
 *
 * The focused row inverts to a near-white fill with dark text. Rows with an
 * [adjustListener] also change their value with left/right.
 */
public class SettingsRowView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        /** Receives left/right presses on an adjustable row. */
        public fun interface AdjustListener {
            /** [step] is -1 for backwards (left in LTR layouts) and +1 for forwards. */
            public fun onAdjust(step: Int)
        }

        private val titleView: TextView
        private val summaryView: TextView
        private val valueView: TextView
        private val toggleView: ToggleView

        /** When set, left/right on the focused row call this instead of moving focus. */
        public var adjustListener: AdjustListener? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = resources.getDimensionPixelSize(R.dimen.rozan_row_min_height)
            val horizontal = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_horizontal)
            val vertical = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_vertical)
            setPaddingRelative(horizontal, vertical, horizontal, vertical)
            setBackgroundResource(R.drawable.rozan_row_background)
            LayoutInflater.from(context).inflate(R.layout.rozan_settings_row, this, true)
            titleView = findViewById(R.id.rozan_row_title)
            summaryView = findViewById(R.id.rozan_row_summary)
            valueView = findViewById(R.id.rozan_row_value)
            toggleView = findViewById(R.id.rozan_row_toggle)
        }

        /** The row title. */
        public var title: CharSequence
            get() = titleView.text
            set(value) {
                titleView.text = value
                updateDescription()
            }

        /** Secondary text under the title; null hides it. */
        public var summary: CharSequence?
            get() = summaryView.text.takeIf { summaryView.isVisible }
            set(value) {
                summaryView.text = value
                summaryView.isVisible = !value.isNullOrEmpty()
            }

        /** Trailing value text, for choice rows; null hides it. */
        public var value: CharSequence?
            get() = valueView.text.takeIf { valueView.isVisible }
            set(value) {
                valueView.text = value
                valueView.isVisible = !value.isNullOrEmpty()
                updateDescription()
            }

        /** Switch state for toggle rows; null hides the switch. */
        public var checked: Boolean?
            get() = if (toggleView.isVisible) toggleView.isChecked else null
            set(value) {
                toggleView.isVisible = value != null
                toggleView.isChecked = value == true
                updateDescription()
            }

        private fun updateDescription() {
            val state =
                when {
                    toggleView.isVisible -> {
                        context.getString(if (toggleView.isChecked) R.string.rozan_on else R.string.rozan_off)
                    }

                    valueView.isVisible -> {
                        valueView.text
                    }

                    else -> {
                        null
                    }
                }
            contentDescription = if (state == null) titleView.text else "${titleView.text}, $state"
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean {
            val listener = adjustListener
            if (listener != null && isEnabled) {
                val forward = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        listener.onAdjust(-forward)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        listener.onAdjust(forward)
                        return true
                    }
                }
            }
            return super.onKeyDown(keyCode, event)
        }
    }
