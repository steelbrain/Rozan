package ai.aneesiqbal.rozan.airplay.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PropertiesFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun missingFileReadsEmpty() {
        val file = PropertiesFile(File(temp.root, "nested/settings.properties"))
        assertNull(file.read().getProperty("x"))
    }

    @Test
    fun updateWritesAndAnotherInstanceReadsIt() {
        val path = File(temp.root, "nested/settings.properties")
        PropertiesFile(path).update {
            it.setProperty("name", "Mango Kiwi Plum Fig")
            true
        }
        assertEquals("Mango Kiwi Plum Fig", PropertiesFile(path).read().getProperty("name"))
        assertFalse(File(path.parentFile, "settings.properties.tmp").exists())
    }

    @Test
    fun updateReturningFalseDoesNotWrite() {
        val path = File(temp.root, "settings.properties")
        PropertiesFile(path).update { false }
        assertFalse(path.exists())
    }

    @Test
    fun unicodeAndSpecialCharactersRoundTrip() {
        val path = File(temp.root, "settings.properties")
        val name = "Anna’s TV = #1 ü \\ end"
        PropertiesFile(path).update {
            it.setProperty("name", name)
            true
        }
        assertEquals(name, PropertiesFile(path).read().getProperty("name"))
    }

    @Test
    fun concurrentReadModifyWritesLoseNothing() {
        val path = File(temp.root, "counter.properties")
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    // A separate instance each time, like separate processes would have.
                    PropertiesFile(path).update { properties ->
                        val count = properties.getProperty("count")?.toInt() ?: 0
                        properties.setProperty("count", (count + 1).toString())
                        true
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        assertEquals((threads * perThread).toString(), PropertiesFile(path).read().getProperty("count"))
    }

    @Test
    fun garbageFileReadsAsEmptyOrPartialWithoutThrowing() {
        val path = File(temp.root, "settings.properties")
        path.writeBytes(ByteArray(256) { (it * 37).toByte() })
        PropertiesFile(path).read()
        path.writeText("bad=\\uZZZZ")
        assertNull(PropertiesFile(path).read().getProperty("bad"))
    }
}
