package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.R
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import ai.aneesiqbal.rozan.ui.R as UiR

/**
 * One input in the inputs menu: its icon, its name, the port a CEC device is
 * on, and a state word. Looks like a settings row; an input in standby or not
 * connected is dimmed but can still be selected.
 */
internal class InputRowView(
    context: Context,
) : LinearLayout(context) {
    private val iconView: ImageView
    private val titleView: TextView
    private val detailView: TextView
    private val stateView: TextView

    /** The input this row shows. */
    var entry: InputEntry? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = resources.getDimensionPixelSize(UiR.dimen.rozan_row_min_height)
        val horizontal = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_horizontal)
        val vertical = resources.getDimensionPixelSize(UiR.dimen.rozan_row_padding_vertical)
        setPaddingRelative(horizontal, vertical, horizontal, vertical)
        setBackgroundResource(UiR.drawable.rozan_row_background)
        LayoutInflater.from(context).inflate(R.layout.inputs_row, this, true)
        iconView = findViewById(R.id.input_icon)
        titleView = findViewById(R.id.input_title)
        detailView = findViewById(R.id.input_detail)
        stateView = findViewById(R.id.input_state)
    }

    /** Shows [entry] with [icon] (none if null). */
    fun bind(
        entry: InputEntry,
        icon: Drawable?,
    ) {
        this.entry = entry
        iconView.setImageDrawable(icon)
        iconView.isVisible = icon != null
        titleView.text = entry.title
        detailView.text = entry.detail
        detailView.isVisible = entry.detail != null
        val state =
            when (entry.state) {
                InputState.CONNECTED -> null
                InputState.STANDBY -> context.getString(R.string.inputs_state_standby)
                InputState.DISCONNECTED -> context.getString(R.string.inputs_state_disconnected)
            }
        stateView.text = state
        stateView.isVisible = state != null
        val dimmed = state != null
        titleView.setTextColor(
            context.getColorStateList(if (dimmed) R.color.inputs_title_dimmed else UiR.color.rozan_row_title),
        )
        iconView.imageAlpha = if (dimmed) DIMMED_ICON_ALPHA else OPAQUE
        contentDescription = listOfNotNull(entry.title, entry.detail, state).joinToString(", ")
    }

    /** Whether the row is drawn dimmed (standby or not connected). For tests. */
    val isDimmed: Boolean get() = stateView.isVisible

    /** The state word shown, or null for a connected input. For tests. */
    val stateText: CharSequence? get() = stateView.text.takeIf { stateView.isVisible }

    private companion object {
        const val OPAQUE = 255
        const val DIMMED_ICON_ALPHA = 128
    }
}
