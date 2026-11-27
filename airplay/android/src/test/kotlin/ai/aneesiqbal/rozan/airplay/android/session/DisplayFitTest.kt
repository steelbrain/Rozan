package ai.aneesiqbal.rozan.airplay.android.session

import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFitTest {
    @Test
    fun fullHdIsReportedAsIs() {
        assertEquals(DisplayInfo(1920, 1080, 60), DisplayFit.fit(1920, 1080, 60f))
    }

    @Test
    fun uhdIsCappedKeepingAspectAndRefresh() {
        assertEquals(DisplayInfo(1920, 1080, 50), DisplayFit.fit(3840, 2160, 50f))
    }

    @Test
    fun smallerModesAreNotScaledUp() {
        assertEquals(DisplayInfo(1280, 720, 60), DisplayFit.fit(1280, 720, 59.94f))
    }

    @Test
    fun ultraWideIsLimitedByWidth() {
        assertEquals(DisplayInfo(1920, 822, 120), DisplayFit.fit(5120, 2192, 119.88f))
    }

    @Test
    fun portraitPanelsAreCappedTheOtherWayRound() {
        assertEquals(DisplayInfo(1080, 1920, 60), DisplayFit.fit(2160, 3840, 60f))
    }

    @Test
    fun unknownModeFallsBackToFullHdSixty() {
        assertEquals(DisplayInfo(1920, 1080, 60), DisplayFit.fit(0, 0, Float.NaN))
        assertEquals(DisplayInfo(1920, 1080, 24), DisplayFit.fit(-1, 1080, 23.976f))
    }
}
