package ai.aneesiqbal.rozan.launcher.backdrop

import ai.aneesiqbal.rozan.launcher.targetContext
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the photos shipped in `assets/backdrops/` against what the app needs from them. */
@RunWith(AndroidJUnit4::class)
class BundledBackdropsTest {
    private val source = BackdropSource(targetContext)

    @Test
    fun everyPhotoHasACredit() {
        val images = source.loadImages()

        assertFalse("the manifest lists photos", images.isEmpty())
        assertEquals("file names are unique", images.size, images.map { it.file }.toSet().size)
        for (image in images) {
            assertTrue("${image.file} has a title", image.title.isNotBlank())
            assertTrue("${image.file} has an author", image.author.isNotBlank())
            assertTrue("${image.file} has a license", image.license.isNotBlank())
            assertTrue("${image.file} has an https source", image.sourceUrl.startsWith("https://"))
        }
    }

    @Test
    fun everyPhotoDecodesToTheRequestedSize() {
        for (image in source.loadImages()) {
            val bitmap = checkNotNull(source.decode(image, WIDTH, HEIGHT)) { "${image.file} does not decode" }

            assertEquals(WIDTH, bitmap.width)
            assertEquals(HEIGHT, bitmap.height)
            assertEquals(Bitmap.Config.HARDWARE, bitmap.config)
            bitmap.recycle()
        }
    }

    private companion object {
        // Small, and not 16:9, so the centre crop is exercised too.
        const val WIDTH = 320
        const val HEIGHT = 200
    }
}
