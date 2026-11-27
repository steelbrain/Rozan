package ai.aneesiqbal.rozan.launcher.inputs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputPickersTest {
    private val viewInputs = PickerQuery(InputPickers.ACTION_VIEW_INPUTS)
    private val fireTvEdition =
        PickerQuery(InputPickers.ACTION_FIRE_NAVIGATE_TO_INPUTS, packageName = InputPickers.FIRE_LAUNCHER)
    private val fireCube =
        PickerQuery(null, packageName = InputPickers.FIRE_INPUT_SERVICE, className = InputPickers.FIRE_INPUT_CHOOSER)

    /** The package manager of a device, as far as the pickers go. */
    private class FakeResolver(
        private val answers: Map<PickerQuery, List<PickerActivity>> = emptyMap(),
        private val defaults: Map<PickerQuery, PickerActivity> = emptyMap(),
        private val granted: Set<String> = emptySet(),
        private val homes: Set<String> = emptySet(),
    ) : PickerResolver {
        val asked = ArrayList<PickerQuery>()

        override fun query(query: PickerQuery): List<PickerActivity> {
            asked += query
            return answers[query].orEmpty()
        }

        override fun systemDefault(query: PickerQuery): PickerActivity? = defaults[query]

        override fun isGranted(permission: String): Boolean = permission in granted

        override fun homePackages(): Set<String> = homes
    }

    private fun activity(
        packageName: String,
        className: String = "$packageName.Picker",
        exported: Boolean = true,
        permission: String? = null,
        system: Boolean = true,
    ) = PickerActivity(packageName, className, exported, permission, system)

    private fun PickerActivity.target(action: String?) = PickerTarget(action, packageName, className)

    private val liveTv = activity("com.android.tv", "com.android.tv.SelectInputActivity")
    private val classicHome =
        activity("com.google.android.tvlauncher", "com.google.android.tvlauncher.inputs.InputsPanelActivity")
    private val vendor = activity("com.vendor.tv", "com.vendor.tv.InputSelectionActivity")

    @Test
    fun `nothing answers, nothing is found`() {
        val resolver = FakeResolver()

        assertEquals(emptyList(), InputPickers.find(resolver))
        assertEquals(listOf(viewInputs, fireTvEdition, fireCube), resolver.asked, "every candidate, in order")
    }

    @Test
    fun `the Live TV action comes first, then Fire TV Edition, then the Fire TV Cube`() {
        val fireHome = activity(InputPickers.FIRE_LAUNCHER, "com.amazon.tv.launcher.ui.InputsActivity")
        val cube = activity(InputPickers.FIRE_INPUT_SERVICE, InputPickers.FIRE_INPUT_CHOOSER)
        val resolver =
            FakeResolver(
                answers =
                    mapOf(
                        fireCube to listOf(cube),
                        fireTvEdition to listOf(fireHome),
                        viewInputs to listOf(liveTv),
                    ),
            )

        assertEquals(
            listOf(
                liveTv.target(InputPickers.ACTION_VIEW_INPUTS),
                fireHome.target(InputPickers.ACTION_FIRE_NAVIGATE_TO_INPUTS),
                cube.target(null),
            ),
            InputPickers.find(resolver),
        )
    }

    @Test
    fun `the Fire TV Cube's chooser alone is found, started by component without an action`() {
        val cube = activity(InputPickers.FIRE_INPUT_SERVICE, InputPickers.FIRE_INPUT_CHOOSER)

        assertEquals(
            listOf(PickerTarget(null, InputPickers.FIRE_INPUT_SERVICE, InputPickers.FIRE_INPUT_CHOOSER)),
            InputPickers.find(FakeResolver(answers = mapOf(fireCube to listOf(cube)))),
        )
    }

    @Test
    fun `an activity that is not exported does not count`() {
        val hidden = activity(InputPickers.FIRE_INPUT_SERVICE, InputPickers.FIRE_INPUT_CHOOSER, exported = false)
        val resolver =
            FakeResolver(
                answers = mapOf(viewInputs to listOf(liveTv.copy(exported = false)), fireCube to listOf(hidden)),
            )

        assertEquals(emptyList(), InputPickers.find(resolver))
    }

    @Test
    fun `an activity behind a permission counts only if Rozan holds it`() {
        val tune = "com.amazon.tv.permission.TUNE_INPUT"
        val granted = "android.permission.EXAMPLE_GRANTED"
        val protectedPicker = liveTv.copy(permission = tune)
        val allowedPicker = vendor.copy(permission = granted)
        val resolver =
            FakeResolver(
                answers = mapOf(viewInputs to listOf(protectedPicker, allowedPicker)),
                granted = setOf(granted),
            )

        assertEquals(listOf(allowedPicker.target(InputPickers.ACTION_VIEW_INPUTS)), InputPickers.find(resolver))
    }

    @Test
    fun `only system apps count, even when nothing else answers`() {
        val sideloaded = activity("com.example.launcher", system = false)
        val resolver = FakeResolver(answers = mapOf(viewInputs to listOf(sideloaded)))

        assertEquals(emptyList(), InputPickers.find(resolver))
        assertEquals(
            listOf(liveTv.target(InputPickers.ACTION_VIEW_INPUTS)),
            InputPickers.find(FakeResolver(answers = mapOf(viewInputs to listOf(sideloaded, liveTv)))),
        )
    }

    @Test
    fun `of several system pickers, the one a plain start opens comes first`() {
        val resolver =
            FakeResolver(
                answers = mapOf(viewInputs to listOf(liveTv, classicHome, vendor)),
                defaults = mapOf(viewInputs to vendor),
                homes = setOf(classicHome.packageName),
            )

        assertEquals(
            listOf(vendor, classicHome, liveTv).map { it.target(InputPickers.ACTION_VIEW_INPUTS) },
            InputPickers.find(resolver),
        )
    }

    @Test
    fun `with no default, the stock home's picker comes first, then the package manager's order`() {
        // Where nothing is preferred, a plain start resolves to the chooser, which is none of the pickers.
        val chooser = activity("android", "com.android.internal.app.ResolverActivity")
        val resolver =
            FakeResolver(
                answers = mapOf(viewInputs to listOf(liveTv, vendor, classicHome)),
                defaults = mapOf(viewInputs to chooser),
                homes = setOf(classicHome.packageName, "ai.aneesiqbal.rozan.launcher"),
            )

        assertEquals(
            listOf(classicHome, liveTv, vendor).map { it.target(InputPickers.ACTION_VIEW_INPUTS) },
            InputPickers.find(resolver),
        )
        assertEquals(
            listOf(liveTv, vendor).map { it.target(InputPickers.ACTION_VIEW_INPUTS) },
            InputPickers.find(FakeResolver(answers = mapOf(viewInputs to listOf(liveTv, vendor)))),
        )
    }

    @Test
    fun `Rozan's menu wins, and the TV's picker alone opens from the button only on Fire OS`() {
        val entries = listOf(InputEntry("hdmi1", "HDMI 1", null, InputState.CONNECTED))
        val pickers = listOf(liveTv.target(InputPickers.ACTION_VIEW_INPUTS))

        for (fireOs in listOf(false, true)) {
            assertEquals(InputsButtonMode.MENU, InputsButtonMode.of(entries, pickers, fireOs), "fireOs=$fireOs")
            assertEquals(InputsButtonMode.MENU, InputsButtonMode.of(entries, emptyList(), fireOs), "fireOs=$fireOs")
            assertEquals(
                InputsButtonMode.HIDDEN,
                InputsButtonMode.of(emptyList(), emptyList(), fireOs),
                "fireOs=$fireOs",
            )
        }
        assertEquals(InputsButtonMode.PICKER, InputsButtonMode.of(emptyList(), pickers, fireOs = true), "Fire OS")
        assertEquals(
            InputsButtonMode.HIDDEN,
            InputsButtonMode.of(emptyList(), pickers, fireOs = false),
            "a box: the system's lists read the same empty input list",
        )
    }

    @Test
    fun `a picker that fails to start is skipped until the apps change`() {
        val first = classicHome.target(InputPickers.ACTION_VIEW_INPUTS)
        val second = liveTv.target(InputPickers.ACTION_VIEW_INPUTS)
        val pickers = SystemPickers()
        pickers.update(listOf(first, second))
        val started = ArrayList<PickerTarget>()

        assertTrue(
            pickers.open {
                started += it
                it == second
            },
            "the next picker opens when the first fails",
        )
        assertEquals(listOf(first, second), started)
        assertEquals(listOf(second), pickers.targets)

        // Looking again (the home screen came back) finds both; the failed one stays out.
        pickers.update(listOf(first, second))
        assertEquals(listOf(second), pickers.targets)

        started.clear()
        assertFalse(
            pickers.open {
                started += it
                false
            },
            "nothing opens",
        )
        assertEquals(listOf(second), started)
        assertEquals(emptyList(), pickers.targets)
        assertEquals(
            InputsButtonMode.HIDDEN,
            InputsButtonMode.of(emptyList(), pickers.targets, fireOs = true),
            "the button goes",
        )

        started.clear()
        assertFalse(
            pickers.open {
                started += it
                true
            },
            "with nothing left, nothing is tried",
        )
        assertEquals(emptyList(), started)

        pickers.forgetFailures()
        assertEquals(listOf(first, second), pickers.targets)
        assertEquals(
            InputsButtonMode.PICKER,
            InputsButtonMode.of(emptyList(), pickers.targets, fireOs = true),
            "the button is back",
        )
        assertTrue(pickers.open { true })
    }

    @Test
    fun `a picker that is no longer found is not offered`() {
        val pickers = SystemPickers()
        pickers.update(listOf(liveTv.target(InputPickers.ACTION_VIEW_INPUTS)))
        pickers.update(emptyList())

        assertEquals(emptyList(), pickers.targets)
        assertFalse(pickers.open { true })
    }
}
