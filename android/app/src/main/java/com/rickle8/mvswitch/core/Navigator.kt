package com.rickle8.mvswitch.core

enum class Key { LEFT, RIGHT, UP, DOWN, SELECT, BACK, HOME, PLAY, PAUSE, VOL_UP, VOL_DOWN, MUTE }

interface Remote {
    fun press(key: Key)
}

/** Box numbers are reading order (left to right, top to bottom). */
val LAYOUTS: Map<Int, Map<Int, Map<Key, Int>>> = mapOf(
    2 to mapOf(1 to mapOf(Key.RIGHT to 2), 2 to mapOf(Key.LEFT to 1)),
    3 to mapOf( // big game left, two stacked right
        1 to mapOf(Key.RIGHT to 2),
        2 to mapOf(Key.LEFT to 1, Key.DOWN to 3),
        3 to mapOf(Key.LEFT to 1, Key.UP to 2),
    ),
    4 to mapOf( // 2x2 grid
        1 to mapOf(Key.RIGHT to 2, Key.DOWN to 3),
        2 to mapOf(Key.LEFT to 1, Key.DOWN to 4),
        3 to mapOf(Key.RIGHT to 4, Key.UP to 1),
        4 to mapOf(Key.LEFT to 3, Key.UP to 2),
    ),
)

fun keyPath(neighbors: Map<Int, Map<Key, Int>>, start: Int, goal: Int): List<Key> {
    if (start == goal) return emptyList()
    val seen = mutableSetOf(start)
    val queue = ArrayDeque(listOf(start to emptyList<Key>()))
    while (queue.isNotEmpty()) {
        val (box, path) = queue.removeFirst()
        for ((key, next) in neighbors[box].orEmpty()) {
            if (next in seen) continue
            if (next == goal) return path + key
            seen += next
            queue.addLast(next to path + key)
        }
    }
    throw IllegalArgumentException("No path from box $start to box $goal")
}

/**
 * Shortest key sequence that lands on [target] no matter which box is
 * highlighted now (edges don't wrap). In a 2x2 grid that's always 2 presses.
 */
fun syncPath(neighbors: Map<Int, Map<Key, Int>>, target: Int): List<Key>? {
    val boxes = neighbors.keys.sorted()
    val bit = boxes.withIndex().associate { (i, b) -> b to (1 shl i) }
    val goal = bit[target] ?: return null
    val start = boxes.fold(0) { acc, b -> acc or bit.getValue(b) }
    val keys = listOf(Key.UP, Key.LEFT, Key.DOWN, Key.RIGHT)
    val prev = HashMap<Int, Pair<Int, Key>>()
    val queue = ArrayDeque(listOf(start))
    val seen = hashSetOf(start)
    while (queue.isNotEmpty()) {
        val set = queue.removeFirst()
        if (set == goal) {
            val path = ArrayList<Key>()
            var cur = set
            while (cur != start) { val (p, k) = prev.getValue(cur); path += k; cur = p }
            return path.reversed()
        }
        for (k in keys) {
            var next = 0
            for (b in boxes) if (set and bit.getValue(b) != 0) next = next or bit.getValue(neighbors[b]?.get(k) ?: b)
            if (seen.add(next)) { prev[next] = set to k; queue.addLast(next) }
        }
    }
    return null
}

/** Where the highlight ends up after pressing keys (edges don't wrap). */
fun simulate(neighbors: Map<Int, Map<Key, Int>>, start: Int, keys: List<Key>): Int =
    keys.fold(start) { box, k -> neighbors[box]?.get(k) ?: box }

/**
 * Tracks which multiview box is highlighted (and therefore has audio).
 * Homing: before each switch it presses UP and LEFT enough to pin the
 * highlight in the top-left box, so it can never lose track.
 */
class Navigator(
    private val remote: Remote,
    val neighbors: Map<Int, Map<Key, Int>>,
    startBox: Int = 1,
    private val keyDelayMs: Long = 350,
    home: Boolean = true,
    homePresses: Int = 2,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    var current: Int? = startBox
    /** Per target box: keys that reach it from anywhere. */
    private val sync: Map<Int, List<Key>>? =
        if (home) neighbors.keys.associateWith { syncPath(neighbors, it) ?: emptyList() }
            .takeIf { m -> m.values.all { it.isNotEmpty() } || neighbors.size == 1 } else null
    val homeSeq: List<Key> =
        if (home) listOf(Key.UP, Key.LEFT).flatMap { k -> List(homePresses) { k } } else emptyList()
    val homeBox: Int?

    init {
        homeBox = if (homeSeq.isEmpty()) null else {
            val ends = neighbors.keys.map { simulate(neighbors, it, homeSeq) }.toSet()
            require(ends.size == 1) { "Homing keys don't land on one box; raise home presses" }
            ends.first()
        }
    }

    private fun pressAll(keys: List<Key>) {
        for (k in keys) {
            remote.press(k)
            sleep(keyDelayMs)
        }
    }

    fun resync() {
        val s = sync?.get(1)
        if (s != null) {
            pressAll(s)
            current = 1
        } else {
            if (homeSeq.isEmpty()) return
            pressAll(homeSeq)
            current = homeBox
        }
        log("Highlight reset to box $current")
    }

    fun goTo(box: Int) {
        if (box == current) return
        val keys = sync?.get(box) ?: if (homeBox != null) {
            homeSeq + keyPath(neighbors, homeBox, box)
        } else {
            keyPath(neighbors, current ?: 1, box)
        }
        pressAll(keys)
        current = box
    }
}
