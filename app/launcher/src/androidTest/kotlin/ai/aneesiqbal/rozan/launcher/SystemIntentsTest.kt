package ai.aneesiqbal.rozan.launcher

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** On a non-Amazon device the generic settings actions are used (Fire OS paths need real hardware). */
@RunWith(AndroidJUnit4::class)
class SystemIntentsTest {
    @Test
    fun settingsUsesTheGenericActionOffFireOs() {
        assertEquals(listOf(Settings.ACTION_SETTINGS), SystemIntents.systemSettings(targetContext).map { it.action })
    }

    @Test
    fun networkFallsBackToSettings() {
        assertEquals(
            listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS),
            SystemIntents.networkSettings(targetContext).map { it.action },
        )
    }
}
