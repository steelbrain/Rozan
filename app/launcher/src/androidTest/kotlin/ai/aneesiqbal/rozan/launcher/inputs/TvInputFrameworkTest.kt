package ai.aneesiqbal.rozan.launcher.inputs

import ai.aneesiqbal.rozan.launcher.PACKAGE
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.launcher.device
import ai.aneesiqbal.rozan.launcher.instrumentation
import ai.aneesiqbal.rozan.launcher.launchHome
import ai.aneesiqbal.rozan.launcher.read
import ai.aneesiqbal.rozan.launcher.redirect.HomeSetupReader
import ai.aneesiqbal.rozan.launcher.resetLauncherState
import ai.aneesiqbal.rozan.launcher.shell
import ai.aneesiqbal.rozan.launcher.targetContext
import ai.aneesiqbal.rozan.launcher.waitUntil
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The inputs code against the real TV input framework of the device: the
 * system's TvInputManager lists the test APK's own input
 * ([TestTunerInputService]), reads its custom name and hidden flag from the
 * TV's settings, and reports it coming and going; the passthrough intent goes
 * through the system's resolver to whatever app handles it.
 *
 * An app's input is always a tuner, so the home screen's button must never
 * open Rozan's menu for it; real passthrough inputs exist only on a TV with
 * HDMI ports (docs/INTEROP.md lists the checks for one). Skipped where the
 * device has no TV input service.
 */
@RunWith(AndroidJUnit4::class)
class TvInputFrameworkTest {
    private val testPackage: String get() = instrumentation.context.packageName
    private val restore = ArrayList<String>()

    @Before
    fun setUp() {
        resetLauncherState()
    }

    @After
    fun tearDown() {
        TvInputSources.testSource = null
        for (command in restore.asReversed()) shell(command)
    }

    /** The system's TvInputManager; skips the test where there is none. */
    private fun manager(): TvInputManager {
        val manager = targetContext.getSystemService(TvInputManager::class.java)
        assumeTrue("no TV input service on this device", manager != null)
        return requireNotNull(manager)
    }

    /** The id the system gave the test APK's input. */
    private fun testInputId(manager: TvInputManager): String {
        var id: String? = null
        waitUntil("the test input is listed", timeoutMs = 15_000) {
            id = manager.tvInputList.firstOrNull { it.serviceInfo.packageName == testPackage }?.id
            id != null
        }
        return requireNotNull(id)
    }

    @Test
    fun anAppsInputIsListedAsATunerAndNeverOpensTheMenu() {
        val manager = manager()
        val id = testInputId(manager)
        val source = SystemTvInputSource.create(targetContext)

        val records = source.read()
        Log.i(TAG, "Inputs on this device: $records")
        val record = records.single { it.id == id }
        assertEquals(InputKind.TUNER, record.kind)
        assertFalse(record.passthrough)
        assertEquals("Rozan test tuner", record.label)
        assertNull(record.customLabel)
        assertFalse(record.hidden)
        assertNull(record.parentId)
        assertEquals(InputState.CONNECTED, record.state)
        assertNotNull(
            "the framework falls back to the service's icon",
            requireNotNull(manager.getTvInputInfo(id)).loadIcon(targetContext),
        )
        assertNull("the service's icon is not shown as the input's", source.loadIcon(id))

        val pickers = InputPickers.find(SystemPickerResolver(targetContext))
        val fireOs = HomeSetupReader.isFireOs(targetContext)
        val expected = InputsButtonMode.of(InputMenu.entries(records), pickers, fireOs)
        // Off Fire OS, a picker alone shows no button: it would list the same empty inputs.
        if (!fireOs && records.none { it.passthrough }) assertEquals(InputsButtonMode.HIDDEN, expected)
        if (records.none { it.passthrough }) {
            assertNotEquals(
                "no passthrough inputs, no menu",
                InputsButtonMode.MENU,
                expected,
            )
        }
        launchHome().use { scenario ->
            waitUntil("inputs read") { scenario.read { it.inputsRead } }
            assertEquals(expected, scenario.read { it.inputsMode })
            assertEquals(expected != InputsButtonMode.HIDDEN, scenario.inputsButtonVisible)
        }
    }

    @Test
    fun customNamesAndHiddenInputsComeFromTheTvSettings() {
        val id = testInputId(manager())
        val source = SystemTvInputSource.create(targetContext)
        changeSecureSetting(CUSTOM_LABELS, "${Uri.encode(id)},${Uri.encode("Living room box")}")

        waitUntil("custom name read") { source.read().single { it.id == id }.customLabel == "Living room box" }
        assertFalse(source.read().single { it.id == id }.hidden)

        changeSecureSetting(HIDDEN_INPUTS, "${Uri.encode("other/.Input")}:${Uri.encode(id)}")
        waitUntil("hidden flag read") { source.read().single { it.id == id }.hidden }
    }

    @Test
    fun theCallbackReportsAnInputGoingAndComing() {
        val id = testInputId(manager())
        val source = SystemTvInputSource.create(targetContext)
        val changes = AtomicInteger()
        instrumentation.runOnMainSync { source.start { changes.incrementAndGet() } }
        try {
            // The package manager tells the TV input service, which rebuilds its list and calls back. The
            // shell may switch only whole packages that are not test-only; the tests keep running, since
            // their code runs in Rozan's process.
            restore += "pm enable $testPackage"
            shell("pm disable-user --user 0 $testPackage")
            waitUntil("removal reported", timeoutMs = 15_000) { changes.get() > 0 }
            waitUntil("input gone") { source.read().none { it.id == id } }

            val before = changes.get()
            shell("pm enable $testPackage")
            waitUntil("addition reported", timeoutMs = 15_000) { changes.get() > before }
            waitUntil("input back") { source.read().any { it.id == id } }
        } finally {
            instrumentation.runOnMainSync { source.stop() }
        }
    }

    @Test
    fun selectingAnInputStartsTheAppThatHandlesPassthroughUris() {
        val id = testInputId(manager())
        val uri = TvContract.buildChannelUriForPassthroughInput(id)
        val handler = passthroughHandler(uri)
        assumeTrue("nothing handles passthrough URIs on this device", handler != null)
        Log.i(TAG, "Passthrough URIs open $handler (type ${targetContext.contentResolver.getType(uri)})")
        // The button needs a passthrough input, which only a TV's hardware has: list the real input as one.
        TvInputSources.testSource = FakeTvInputSource(listOf(hdmi(id, "HDMI 1")))

        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            shell("logcat -c")
            device.pressDPadCenter()

            waitUntil("the system started ${handler?.flattenToShortString()}", timeoutMs = 10_000) {
                activityStarts().any { it.contains("cmp=${handler?.flattenToShortString()}") }
            }
            waitUntil("menu closed") { !scenario.menuShowing }
        }
        handler?.let { shell("am force-stop ${it.packageName}") }
    }

    @Test
    fun withoutATvAppTheMenuOpensTheSystemInputList() {
        val id = testInputId(manager())
        val uri = TvContract.buildChannelUriForPassthroughInput(id)
        // Where a TV app handles the URI, take it away, as on a device that has none: the start then
        // really fails. Some emulator images (API 31) have none to begin with.
        val tvApp = passthroughHandler(uri)?.packageName
        if (tvApp != null) {
            assumeTrue("$tvApp is Rozan's own test", tvApp != PACKAGE && tvApp != testPackage)
            restore += "pm enable $tvApp"
            shell("pm disable-user --user 0 $tvApp")
            waitUntil("$tvApp disabled") { passthroughHandler(uri) == null }
        }
        val pickers = InputPickers.find(SystemPickerResolver(targetContext))
        Log.i(TAG, "System input lists without a TV app: ${pickers.map { it.component }}")
        TvInputSources.testSource = FakeTvInputSource(listOf(hdmi(id, "HDMI 1")))

        launchHome().use { scenario ->
            waitUntil("button shown") { scenario.inputsButtonVisible }
            scenario.openMenuWithDpad()
            device.pressDPadCenter()
            waitUntil("message shown") {
                scenario.read { it.findViewById<InputsPanel>(R.id.inputs_panel).messageText } != null
            }
            assertTrue(scenario.menuShowing)
            assumeTrue("no system input list on this device", pickers.isNotEmpty())

            shell("logcat -c")
            device.pressDPadCenter()
            val picker = pickers.first().component.flattenToShortString()
            waitUntil("the system started $picker", timeoutMs = 10_000) {
                activityStarts().any { it.contains("cmp=$picker") }
            }
            waitUntil("menu closed") { !scenario.menuShowing }
            device.pressBack()
        }
    }

    /** The activity a passthrough URI resolves to, with the type the TV provider gives it. */
    private fun passthroughHandler(uri: Uri): ComponentName? {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, targetContext.contentResolver.getType(uri))
        val pm = targetContext.packageManager
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val found =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                // The int-flags overload is the only one before API 33.
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, flags)
            }
        return found.singleOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }

    /** Sets a secure setting for this test, and puts the old value back afterwards. */
    private fun changeSecureSetting(
        key: String,
        value: String,
    ) {
        val old = shell("settings get secure $key").trim()
        restore += if (old == "null") "settings delete secure $key" else "settings put secure $key $old"
        shell("settings put secure $key $value")
    }

    private companion object {
        const val TAG = "TvInputFrameworkTest"
        const val CUSTOM_LABELS = "tv_input_custom_labels"
        const val HIDDEN_INPUTS = "tv_input_hidden_inputs"
    }
}
