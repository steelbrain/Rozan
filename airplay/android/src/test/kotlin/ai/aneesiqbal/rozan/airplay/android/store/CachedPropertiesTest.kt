package ai.aneesiqbal.rozan.airplay.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class CachedPropertiesTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Runs tasks only when [drain] is called, like a writer thread that has not got to them yet. */
    private class HeldExecutor : Executor {
        val tasks = ArrayList<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    private val path by lazy { File(temp.root, "airplay/settings.properties") }
    private val writer = HeldExecutor()
    private val failures = ArrayList<IOException>()

    private fun cached(mainThread: Boolean = true) =
        CachedProperties(PropertiesFile(path), writer, { mainThread }, { failures += it })

    private fun onDisk(key: String): String? = PropertiesFile(path).read().getProperty(key)

    @Test
    fun readsComeFromMemoryAfterTheFirst() {
        PropertiesFile(path).update {
            it.setProperty("enabled", "false")
            true
        }
        val store = cached()
        assertEquals("false", store.get("enabled"))
        path.delete()
        assertEquals("read once, then from memory", "false", store.get("enabled"))
    }

    @Test
    fun aMainThreadWriteIsReadAtOnceAndReachesTheDiskLater() {
        val store = cached(mainThread = true)
        store.set("name", "Mango Kiwi Plum Fig")
        assertEquals("Mango Kiwi Plum Fig", store.get("name"))
        assertNull("not written on the calling thread", onDisk("name"))
        writer.drain()
        assertEquals("Mango Kiwi Plum Fig", onDisk("name"))
    }

    @Test
    fun anUnchangedValueQueuesNoWrite() {
        val store = cached()
        store.set("enabled", "true")
        writer.drain()
        store.set("enabled", "true")
        assertTrue(writer.tasks.isEmpty())
    }

    @Test
    fun writesReachTheDiskInOrder() {
        val store = cached()
        store.set("name", "A")
        store.set("name", "B")
        store.set("name", "A")
        writer.drain()
        assertEquals("A", onDisk("name"))
        assertEquals("A", store.get("name"))
    }

    @Test
    fun aReloadSeesAnotherProcessAndKeepsUnsavedWritesOfThisOne() {
        val store = cached()
        store.get("x")
        store.set("enabled", "false")
        // Another process replaces the file meanwhile.
        PropertiesFile(path).update {
            it.setProperty("name", "Other")
            true
        }
        store.reload()
        assertEquals("Other", store.get("name"))
        assertEquals("the unsaved write survives the reload", "false", store.get("enabled"))
        writer.drain()
        store.reload()
        assertEquals("false", store.get("enabled"))
        assertEquals("Other", onDisk("name"))
        assertEquals("false", onDisk("enabled"))
    }

    @Test
    fun afterSavedRunsAtOnceWhenNothingIsPendingElseAfterTheWrites() {
        val store = cached()
        var ran = false
        store.afterSaved { ran = true }
        assertTrue(ran)

        store.set("enabled", "true")
        var sawOnDisk: String? = null
        store.afterSaved { sawOnDisk = onDisk("enabled") }
        assertNull(sawOnDisk)
        writer.drain()
        assertEquals("true", sawOnDisk)
    }

    @Test
    fun aWriteOffTheMainThreadIsOnDiskWhenTheSetterReturns() {
        val store = CachedProperties(PropertiesFile(path), Executor { it.run() }, { false }, { failures += it })
        store.set("wake_on_connect", "false")
        assertEquals("false", onDisk("wake_on_connect"))
    }

    @Test
    fun getOrCreateStoresOneValueThatEveryInstanceAgreesOn() {
        val first = cached()
        val second = cached()
        assertEquals("Fig", first.getOrCreate("name", "Fig"))
        assertEquals("the stored value wins", "Fig", second.getOrCreate("name", "Plum"))
        assertEquals("Fig", onDisk("name"))
        assertEquals("Fig", second.get("name"))
    }

    @Test
    fun aFailedWriteIsReportedAndKeepsTheValueInMemory() {
        // The settings directory cannot be created: a file is in its place.
        val blocked = File(temp.root, "blocked")
        blocked.writeText("")
        val store =
            CachedProperties(PropertiesFile(File(blocked, "settings.properties")), writer, { true }) {
                failures += it
            }
        store.set("enabled", "false")
        writer.drain()
        assertEquals(1, failures.size)
        assertEquals("false", store.get("enabled"))
        assertFalse(File(blocked, "settings.properties").exists())
    }
}
