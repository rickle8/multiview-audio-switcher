package com.rickle8.mvswitch.core

/** Tell the switching logic what the camera sees on each box (as the app does every frame). */
fun Controller.see(now: Double, vararg boxes: Pair<Int, Sight>) {
    sights = now to boxes.toMap()
}
