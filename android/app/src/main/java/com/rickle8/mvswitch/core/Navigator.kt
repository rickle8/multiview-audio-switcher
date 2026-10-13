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
        if (homeSeq.isEmpty()) return
        pressAll(homeSeq)
        current = homeBox
        log("Highlight reset to box $homeBox")
    }

    fun goTo(box: Int) {
        if (box == current) return
        val keys = if (homeBox != null) {
            homeSeq + keyPath(neighbors, homeBox, box)
        } else {
            keyPath(neighbors, current ?: 1, box)
        }
        pressAll(keys)
        current = box
    }
}
