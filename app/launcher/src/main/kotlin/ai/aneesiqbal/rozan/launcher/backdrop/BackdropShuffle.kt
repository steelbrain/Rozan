package ai.aneesiqbal.rozan.launcher.backdrop

import kotlin.random.Random

/**
 * Picks backdrop indices in random order: every image once per round, a new
 * random order each round, and never the same image twice in a row.
 */
class BackdropShuffle(
    private val count: Int,
    private val random: Random = Random.Default,
) {
    private var order = IntArray(0)
    private var position = 0
    private var last = -1

    init {
        require(count > 0) { "count must be positive" }
    }

    /** Returns the next image index in `0 until count`. */
    fun next(): Int {
        if (count == 1) return 0.also { last = 0 }
        if (position >= order.size) {
            order = IntArray(count) { it }.also { it.shuffle(random) }
            if (order[0] == last) {
                val swapWith = 1 + random.nextInt(count - 1)
                order[0] = order[swapWith]
                order[swapWith] = last
            }
            position = 0
        }
        return order[position++].also { last = it }
    }
}
