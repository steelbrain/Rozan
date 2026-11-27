package ai.aneesiqbal.rozan.launcher.backdrop

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BackdropShuffleTest {
    @Test
    fun `never shows the same image twice in a row`() {
        for (count in 2..6) {
            for (seed in 0 until 50) {
                val shuffle = BackdropShuffle(count, Random(seed))
                var previous = shuffle.next()
                repeat(count * 20) {
                    val next = shuffle.next()
                    assertNotEquals(previous, next, "count=$count seed=$seed")
                    previous = next
                }
            }
        }
    }

    @Test
    fun `every round shows every image once`() {
        val count = 7
        val shuffle = BackdropShuffle(count, Random(42))
        repeat(10) {
            val round = List(count) { shuffle.next() }
            assertEquals((0 until count).toSet(), round.toSet())
        }
    }

    @Test
    fun `single image repeats`() {
        val shuffle = BackdropShuffle(1)
        assertEquals(listOf(0, 0, 0), List(3) { shuffle.next() })
    }

    @Test
    fun `needs at least one image`() {
        assertFailsWith<IllegalArgumentException> { BackdropShuffle(0) }
    }
}
