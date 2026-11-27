package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.NsdBrowser.txt
import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus.Advertising
import ai.aneesiqbal.rozan.airplay.android.net.NsdPublisher
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Publishes a record and finds it again with the system's own DNS-SD browser. */
@RunWith(AndroidJUnit4::class)
class NsdPublisherTest {
    @Test
    fun publishedRecordIsDiscoverableWithItsTxtEntries() {
        val name = "Rozan Test ${Random.nextInt(0x10000).toString(16)}"
        val states = CopyOnWriteArrayList<Advertising>()
        val publisher = NsdPublisher(testContext) { states += it }
        val record = ServiceRecord(name, TYPE, 7123, listOf("model" to "RozanTest", "srcvers" to "0.0", "pk" to "00ff"))
        publisher.publish(listOf(record))
        assertTrue("advertising ($states)", eventually { states.lastOrNull() == Advertising.ADVERTISING })

        val service = NsdBrowser.discover(TYPE) { it.serviceName == name }
        assertNotNull("discovered $name", service)
        val resolved = NsdBrowser.resolve(requireNotNull(service))
        assertEquals(7123, resolved.port)
        assertEquals("RozanTest", resolved.txt()["model"])
        assertEquals("0.0", resolved.txt()["srcvers"])

        publisher.unpublish()
        assertTrue("withdrawn", eventually { states.lastOrNull() == Advertising.NOT_PUBLISHED })
    }

    @Test
    fun republishingReplacesTheRecordSet() {
        val states = CopyOnWriteArrayList<Advertising>()
        val publisher = NsdPublisher(testContext) { states += it }
        val first = "Rozan First ${Random.nextInt(0x10000).toString(16)}"
        val second = "Rozan Second ${Random.nextInt(0x10000).toString(16)}"
        publisher.publish(listOf(ServiceRecord(first, TYPE, 7124, emptyList())))
        assertTrue(eventually { states.lastOrNull() == Advertising.ADVERTISING })
        states.clear()
        publisher.publish(listOf(ServiceRecord(second, TYPE, 7125, emptyList())))
        assertTrue("re-registered ($states)", eventually { states.lastOrNull() == Advertising.ADVERTISING })

        assertNotNull("discovered $second", NsdBrowser.discover(TYPE) { it.serviceName == second })
        publisher.unpublish()
        assertTrue(eventually { states.lastOrNull() == Advertising.NOT_PUBLISHED })
    }

    @Test
    fun aChangedTxtRecordIsRegisteredAgain() {
        // In PIN pairing the receiver gets a new identity key after every session and republishes it.
        val states = CopyOnWriteArrayList<Advertising>()
        val publisher = NsdPublisher(testContext) { states += it }
        val name = "Rozan Key ${Random.nextInt(0x10000).toString(16)}"
        publisher.publish(listOf(ServiceRecord(name, TYPE, 7126, listOf("pk" to "aa11"))))
        assertTrue(eventually { states.lastOrNull() == Advertising.ADVERTISING })
        states.clear()
        publisher.publish(listOf(ServiceRecord(name, TYPE, 7126, listOf("pk" to "bb22"))))
        assertTrue("re-registered ($states)", eventually { states.lastOrNull() == Advertising.ADVERTISING })

        val service = requireNotNull(NsdBrowser.discover(TYPE) { it.serviceName == name }) { "discovered $name" }
        assertEquals("bb22", NsdBrowser.resolve(service).txt()["pk"])
        // Same name, no conflict with its own earlier registration.
        assertEquals(name, service.serviceName)
        publisher.unpublish()
        assertTrue(eventually { states.lastOrNull() == Advertising.NOT_PUBLISHED })
    }

    private companion object {
        const val TYPE = "_rozantest._tcp"
    }
}
