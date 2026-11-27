package ai.aneesiqbal.rozan.launcher.inputs

/** What kind of connector an input is, from `TvInputInfo.getType()`. */
internal enum class InputKind {
    TUNER,
    HDMI,
    DISPLAY_PORT,
    DVI,
    COMPONENT,
    COMPOSITE,
    SVIDEO,
    SCART,
    VGA,
    OTHER,
}

/** `TvInputManager.getInputState()`. */
internal enum class InputState {
    CONNECTED,

    /** Connected, but the device behind it reports that it is in standby (HDMI-CEC). */
    STANDBY,
    DISCONNECTED,
}

/** One input as the system lists it (docs/research/tv-inputs.md, section 1). */
internal data class InputRecord(
    val id: String,
    val kind: InputKind,
    val passthrough: Boolean,
    /** The system's label, such as "HDMI 1", or a CEC device's name. */
    val label: String,
    /** The name the user gave it in the TV's settings, or null. */
    val customLabel: String?,
    /** Hidden by the user in the TV's settings. */
    val hidden: Boolean,
    /** For a device found over HDMI-CEC, the HDMI port input it is connected to. */
    val parentId: String?,
    val state: InputState,
)

/** One row of the inputs menu. */
internal data class InputEntry(
    val id: String,
    val title: String,
    /** The port a CEC device is connected to, such as "HDMI 2"; null for a port. */
    val detail: String?,
    val state: InputState,
)

/**
 * Which inputs the menu lists, in which order and under which names. Pure, so
 * that the rules are tested on the JVM.
 *
 * - Only passthrough inputs (HDMI, composite, component and the like): those
 *   are what an input switch selects. Tuners (antenna, cable) are channels of
 *   the TV's own app and are left out.
 * - Inputs the user hid in the TV's settings are left out.
 * - The user's name for an input wins over the system's.
 * - Order: HDMI first, then the other connectors, each by the system's label
 *   with numbers in numeric order ("HDMI 2" before "HDMI 10"), so the order
 *   follows the ports and does not change when a port is renamed or a device
 *   wakes. A device found over HDMI-CEC comes right after its port.
 */
internal object InputMenu {
    /** The menu's rows for [records]; empty when there is nothing to switch to. */
    fun entries(records: List<InputRecord>): List<InputEntry> {
        val byId = records.associateBy { it.id }
        return records
            .filter { it.passthrough && !it.hidden && it.kind != InputKind.TUNER }
            .map { SortKey(it, byId[it.parentId]) }
            .sortedWith(ORDER)
            .map { key ->
                val record = key.record
                InputEntry(
                    id = record.id,
                    title = titleOf(record),
                    detail = key.parent?.let(::titleOf),
                    state = record.state,
                )
            }
    }

    /** The name shown for [record]: the user's name, else the system's. */
    fun titleOf(record: InputRecord): String =
        record.customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: systemName(record)

    /** The system's name for [record], else its id. Rows sort by it, so renaming a port does not move it. */
    private fun systemName(record: InputRecord): String = record.label.trim().takeIf { it.isNotEmpty() } ?: record.id

    /** A row and the port it hangs off ([parent], null for a port or an input without one). */
    private class SortKey(
        val record: InputRecord,
        val parent: InputRecord?,
    ) {
        /** The port this row sorts under: its parent, or itself. */
        val root: InputRecord get() = parent ?: record
    }

    private val ORDER: Comparator<SortKey> =
        compareBy<SortKey> { it.root.kind.ordinal }
            .thenComparator { a, b -> naturalCompare(systemName(a.root), systemName(b.root)) }
            .thenBy { it.root.id }
            .thenBy { it.parent != null }
            .thenComparator { a, b -> naturalCompare(titleOf(a.record), titleOf(b.record)) }
            .thenBy { it.record.id }

    /**
     * Compares [a] and [b] with runs of digits compared as numbers and the rest
     * compared ignoring case: "HDMI 2" < "HDMI 10", "av" == "AV".
     */
    fun naturalCompare(
        a: String,
        b: String,
    ): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isAsciiDigit()
            val bDigit = b[j].isAsciiDigit()
            if (aDigit && bDigit) {
                val aEnd = digitsEnd(a, i)
                val bEnd = digitsEnd(b, j)
                val byNumber = compareNumbers(a, i, aEnd, b, j, bEnd)
                if (byNumber != 0) return byNumber
                i = aEnd
                j = bEnd
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun digitsEnd(
        s: String,
        start: Int,
    ): Int {
        var end = start
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    /** Compares two runs of digits by value, without parsing (a run may be longer than a Long). */
    private fun compareNumbers(
        a: String,
        aStart: Int,
        aEnd: Int,
        b: String,
        bStart: Int,
        bEnd: Int,
    ): Int {
        var i = aStart
        var j = bStart
        while (i < aEnd - 1 && a[i] == '0') i++
        while (j < bEnd - 1 && b[j] == '0') j++
        val byLength = (aEnd - i).compareTo(bEnd - j)
        if (byLength != 0) return byLength
        while (i < aEnd) {
            val byDigit = a[i].compareTo(b[j])
            if (byDigit != 0) return byDigit
            i++
            j++
        }
        return 0
    }
}
