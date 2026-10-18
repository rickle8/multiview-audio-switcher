package com.rickle8.mvswitch.core

/** Tell the switching logic what the camera sees on each box (as the app does every frame). */
fun Controller.see(now: Double, vararg boxes: Pair<Int, Sight>) {
    val m = boxes.toMap()
    sights = now to m
    camera = now to m.mapValues { it.value.word }
}
