package ai.aneesiqbal.rozan.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A settings row with an editable single-line text field on the trailing side.
 *
 * The field itself takes focus (OK opens the system keyboard); the row takes
 * its focused look from the field, so it highlights like any other row.
 */
public class SettingsTextFieldView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val titleView: TextView = TextView(context)
        private val titleColors = context.getColorStateList(R.color.rozan_row_title)

        /** The editable field. */
        public val editText: EditText

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setAddStatesFromChildren(true)
            minimumHeight = resources.getDimensionPixelSize(R.dimen.rozan_row_min_height)
            val horizontal = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_horizontal)
            val vertical = resources.getDimensionPixelSize(R.dimen.rozan_row_padding_vertical)
            setPaddingRelative(horizontal, vertical, horizontal, vertical)

            val textSize = resources.getDimension(R.dimen.rozan_row_title_text)
            titleView.apply {
                // Coloured from the row's state in drawableStateChanged(): a child cannot
                // duplicate its parent's state while the parent adds states from children.
                setTextColor(titleColors.defaultColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                textAlignment = TEXT_ALIGNMENT_VIEW_START
            }
            addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            editText =
                EditText(context).apply {
                    id = generateViewId()
                    background = null
                    setPadding(0, 0, 0, 0)
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    setTextColor(titleColors)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                    textAlignment = TEXT_ALIGNMENT_VIEW_END
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
            // A single-line field would spend the first Up/Down moving its cursor; leave the row instead.
            editText.setOnKeyListener { field, keyCode, event ->
                val direction =
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> FOCUS_UP
                        KeyEvent.KEYCODE_DPAD_DOWN -> FOCUS_DOWN
                        else -> return@setOnKeyListener false
                    }
                if (event.action == KeyEvent.ACTION_DOWN) field.focusSearch(direction)?.requestFocus(direction)
                true
            }
            val fieldParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            fieldParams.marginStart = resources.getDimensionPixelSize(R.dimen.rozan_row_value_gap)
            addView(editText, fieldParams)
            titleView.labelFor = editText.id
            setBackgroundResource(R.drawable.rozan_row_background)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            titleView.setTextColor(titleColors.getColorForState(drawableState, titleColors.defaultColor))
        }

        /** The row title; it is also the field's accessibility label. */
        public var title: CharSequence
            get() = titleView.text
            set(value) {
                titleView.text = value
            }
    }
