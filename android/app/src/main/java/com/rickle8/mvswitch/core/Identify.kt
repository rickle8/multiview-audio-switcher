package com.rickle8.mvswitch.core

/**
 * Works out which game you're actually hearing from the names the announcers
 * say: players and teams that belong to only one of the games on screen.
 * That way the app knows where the audio is even if the TV highlight wandered.
 */
class GameIdentifier(private val windowSecs: Double = 75.0) {
    private class Hit(val t: Double, val box: Int, val word: String)
    private val hits = ArrayDeque<Hit>()

    /** Names said in the last second; [namesByBox]: each game's player and team names. */
    @Synchronized
    fun heard(words: Collection<String>, now: Double, namesByBox: Map<Int, Set<String>>) {
        for (w in words.map { it.lowercase().trim() }.distinct()) {
            val boxes = namesByBox.filterValues { w in it }.keys
            if (boxes.size == 1) hits.addLast(Hit(now, boxes.first(), w))
        }
        while (hits.isNotEmpty() && now - hits.first().t > windowSecs) hits.removeFirst()
    }

    /** A name from this game was said in the last [secs] seconds: the game is on, not an ad. */
    @Synchronized
    fun heardRecently(box: Int, now: Double, secs: Double): String? =
        hits.lastOrNull { it.box == box && now - it.t <= secs }?.word

    /** The audio changed games: what was heard before doesn't count. */
    @Synchronized
    fun clear() = hits.clear()

    /**
     * The game being heard, once it's clear: several different names from one
     * game and next to none from any other.
     */
    @Synchronized
    fun identified(now: Double): Pair<Int, List<String>>? {
        val recent = hits.filter { now - it.t <= windowSecs }
        if (recent.isEmpty()) return null
        val byBox = recent.groupBy { it.box }.mapValues { (_, v) -> v.map { it.word }.distinct() }
        val ranked = byBox.entries.sortedByDescending { it.value.size }
        val top = ranked.first()
        val second = ranked.getOrNull(1)?.value?.size ?: 0
        // Sure only with three names from one game and none (or far fewer) from the others.
        val n = top.value.size
        if (!((n >= 3 && second == 0) || (n >= 4 && second <= 1))) return null
        return top.key to top.value
    }
}
