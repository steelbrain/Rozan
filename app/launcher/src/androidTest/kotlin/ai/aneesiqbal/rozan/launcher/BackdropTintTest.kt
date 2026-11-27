package ai.aneesiqbal.rozan.launcher

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * With a lower row focused, the backdrop draws itself darkened instead of having the grid scrim blended
 * over it. The screen must look the same: the two are compared pixel by pixel, from screenshots of one
 * process (the same photo), and the screenshots are kept in the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class BackdropTintTest {
    @Before
    fun setUp() {
        resetLauncherState()
    }

    @Test
    fun theDarkenedBackdropLooksLikeTheScrimOverIt() {
        launchHome().use { scenario ->
            device.pressDPadDown()
            waitUntil("the backdrop is darkened in place of the scrim") { scenario.read { it.isBackdropTinted } }
            val tinted = stableScreenshot()

            scenario.onActivity { it.setBackdropTintAllowed(false) }
            waitUntil("the scrim is a view again") { scenario.read { !it.isBackdropTinted } }
            val scrim = stableScreenshot()
            scenario.onActivity { it.setBackdropTintAllowed(true) }

            val dir = targetContext.getExternalFilesDir(null)
            if (dir != null) {
                save(tinted, File(dir, "backdrop-tinted.png"))
                save(scrim, File(dir, "backdrop-scrim.png"))
            }
            assertEquals(scrim.width, tinted.width)
            assertEquals(scrim.height, tinted.height)
            val a = pixels(tinted)
            val b = pixels(scrim)
            var differing = 0
            var largest = 0
            for (i in a.indices) {
                if (a[i] == b[i]) continue
                differing++
                for (shift in intArrayOf(0, 8, 16)) {
                    largest = max(largest, abs((a[i] shr shift and 0xFF) - (b[i] shr shift and 0xFF)))
                }
            }
            Log.i(TAG, "${a.size} pixels, $differing differ, largest channel difference $largest")
            assertTrue("$differing of ${a.size} pixels differ, by up to $largest", largest <= MAX_CHANNEL_DIFFERENCE)
        }
    }

    /** A screenshot once two in a row are the same (the last change has reached the screen). */
    private fun stableScreenshot(): Bitmap {
        var previous = screenshot()
        repeat(SCREENSHOT_ATTEMPTS) {
            val next = screenshot()
            if (next.sameAs(previous)) return next
            previous = next
        }
        return previous
    }

    private fun screenshot(): Bitmap {
        device.waitForIdle()
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot", shot)
        return shot.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun save(
        bitmap: Bitmap,
        file: File,
    ) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private companion object {
        const val TAG = "BackdropTintTest"
        const val SCREENSHOT_ATTEMPTS = 10
        const val PNG_QUALITY = 100

        /**
         * Rounding: the scrim is blended into the 8-bit framebuffer, the tint is applied in the shader
         * before it, so a channel may come out a step or two apart: one on the ARM emulators and
         * SwiftShader on a Mac, two on CI's x86_64 SwiftShader (9% of pixels). Two steps of 255 are
         * far below what anyone can see on a TV; a wrong tint would be tens of steps off.
         */
        const val MAX_CHANNEL_DIFFERENCE = 2
    }
}
