package ai.aneesiqbal.rozan.launcher.inputs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputMenuTest {
    private fun input(
        id: String,
        label: String = id,
        kind: InputKind = InputKind.HDMI,
        passthrough: Boolean = kind != InputKind.TUNER,
        customLabel: String? = null,
        hidden: Boolean = false,
        parentId: String? = null,
        state: InputState = InputState.CONNECTED,
    ) = InputRecord(id, kind, passthrough, label, customLabel, hidden, parentId, state)

    private fun titles(records: List<InputRecord>) = InputMenu.entries(records).map { it.title }

    @Test
    fun `lists passthrough inputs only`() {
        val records =
            listOf(
                input("tuner", "TV", kind = InputKind.TUNER),
                input("app", "Sample channels", kind = InputKind.OTHER, passthrough = false),
                input("hdmi1", "HDMI 1"),
                input("av", "AV", kind = InputKind.COMPOSITE),
            )

        assertEquals(listOf("HDMI 1", "AV"), titles(records))
    }

    @Test
    fun `leaves out hidden inputs`() {
        val records = listOf(input("hdmi1", "HDMI 1"), input("hdmi2", "HDMI 2", hidden = true))

        assertEquals(listOf("HDMI 1"), titles(records))
    }

    @Test
    fun `the user's name wins over the system's`() {
        val records =
            listOf(
                input("hdmi1", "HDMI 1", customLabel = "Apple TV"),
                input("hdmi2", "HDMI 2", customLabel = "  "),
                input("hdmi3", "HDMI 3", customLabel = "  Xbox \n"),
                input("hdmi4", "  "),
            )

        assertEquals(listOf("Apple TV", "HDMI 2", "Xbox", "hdmi4"), titles(records))
    }

    @Test
    fun `orders by connector, then port number, not by the user's names`() {
        val records =
            listOf(
                input("vga", "PC", kind = InputKind.VGA),
                input("av", "AV", kind = InputKind.COMPOSITE),
                input("hdmi10", "HDMI 10"),
                input("component", "Component", kind = InputKind.COMPONENT),
                input("hdmi2", "HDMI 2", customLabel = "Zeta"),
                input("dp", "DisplayPort", kind = InputKind.DISPLAY_PORT),
                input("hdmi1", "HDMI 1", customLabel = "Xbox"),
                input("other", "Mystery", kind = InputKind.OTHER),
            )

        assertEquals(
            listOf("Xbox", "Zeta", "HDMI 10", "DisplayPort", "Component", "AV", "PC", "Mystery"),
            titles(records),
        )
    }

    @Test
    fun `the order does not depend on the order the system lists them in or their state`() {
        val records =
            listOf(
                input("hdmi3", "HDMI 3", state = InputState.CONNECTED),
                input("hdmi1", "HDMI 1", state = InputState.DISCONNECTED),
                input("hdmi2", "HDMI 2", state = InputState.STANDBY),
            )

        assertEquals(titles(records), titles(records.reversed()))
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3"), titles(records))
    }

    @Test
    fun `a CEC device comes right after its port and names the port`() {
        val records =
            listOf(
                input("hdmi2", "HDMI 2"),
                input("cec-ps5", "PlayStation 5", parentId = "hdmi2", state = InputState.STANDBY),
                input("hdmi1", "HDMI 1", customLabel = "Soundbar"),
                input("cec-avr", "AV receiver", parentId = "hdmi1"),
                input("cec-bluray", "Blu-ray", parentId = "hdmi1"),
            )

        val entries = InputMenu.entries(records)

        assertEquals(listOf("Soundbar", "AV receiver", "Blu-ray", "HDMI 2", "PlayStation 5"), entries.map { it.title })
        assertEquals(listOf(null, "Soundbar", "Soundbar", null, "HDMI 2"), entries.map { it.detail })
        assertEquals(InputState.STANDBY, entries.last().state)
    }

    @Test
    fun `a CEC device behind a hidden port stays where the port was`() {
        val records =
            listOf(
                input("hdmi1", "HDMI 1"),
                input("hdmi2", "HDMI 2", hidden = true),
                input("cec-ps5", "PlayStation 5", parentId = "hdmi2"),
                input("hdmi3", "HDMI 3"),
            )

        val entries = InputMenu.entries(records)

        assertEquals(listOf("HDMI 1", "PlayStation 5", "HDMI 3"), entries.map { it.title })
        assertEquals("HDMI 2", entries[1].detail)
    }

    @Test
    fun `a parent the system does not list is ignored`() {
        val entries = InputMenu.entries(listOf(input("cec", "Chromecast", parentId = "gone")))

        assertEquals(listOf("Chromecast"), entries.map { it.title })
        assertNull(entries.single().detail)
    }

    @Test
    fun `keeps ids and states`() {
        val entries =
            InputMenu.entries(
                listOf(
                    input("a", "HDMI 1", state = InputState.DISCONNECTED),
                    input("b", "HDMI 2", state = InputState.STANDBY),
                ),
            )

        assertEquals(listOf("a", "b"), entries.map { it.id })
        assertEquals(listOf(InputState.DISCONNECTED, InputState.STANDBY), entries.map { it.state })
    }

    @Test
    fun `the menu is offered only when there is an input to switch to`() {
        fun mode(records: List<InputRecord>) =
            InputsButtonMode.of(InputMenu.entries(records), pickers = emptyList(), fireOs = false)

        assertEquals(InputsButtonMode.HIDDEN, mode(emptyList()), "no inputs (a streaming stick)")
        assertEquals(
            InputsButtonMode.HIDDEN,
            mode(listOf(input("tuner", "TV", kind = InputKind.TUNER))),
            "a tuner only",
        )
        assertEquals(
            InputsButtonMode.HIDDEN,
            mode(listOf(input("app", "Channels", kind = InputKind.OTHER, passthrough = false))),
            "an app's input only",
        )
        assertEquals(
            InputsButtonMode.HIDDEN,
            mode(listOf(input("hdmi1", "HDMI 1", hidden = true))),
            "every input hidden",
        )
        assertEquals(InputsButtonMode.MENU, mode(listOf(input("hdmi1", "HDMI 1", state = InputState.DISCONNECTED))))
        assertEquals(InputsButtonMode.MENU, mode(listOf(input("av", "AV", kind = InputKind.COMPOSITE))))
    }

    @Test
    fun `natural order compares numbers by value and letters ignoring case`() {
        val sorted =
            listOf("HDMI 10", "hdmi 2", "HDMI 1", "HDMI 02", "HDMI", "AV 1", "av", "HDMI 100000000000000000000")
                .sortedWith(InputMenu::naturalCompare)

        assertEquals(
            listOf("av", "AV 1", "HDMI", "HDMI 1", "hdmi 2", "HDMI 02", "HDMI 10", "HDMI 100000000000000000000"),
            sorted,
        )
        assertEquals(0, InputMenu.naturalCompare("HDMI 2", "hdmi 2"))
        assertEquals(0, InputMenu.naturalCompare("", ""))
        assertTrue(InputMenu.naturalCompare("", "a") < 0)
    }
}
