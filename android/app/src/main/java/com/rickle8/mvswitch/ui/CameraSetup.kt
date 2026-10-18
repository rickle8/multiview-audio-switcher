package com.rickle8.mvswitch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.rickle8.mvswitch.core.GrayFrame
import com.rickle8.mvswitch.core.boxRects
import com.rickle8.mvswitch.core.Homography
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState
import kotlinx.coroutines.delay

private fun GrayFrame.toBitmap(): Bitmap {
    val px = IntArray(width * height) { i -> val g = pixels[i].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/** Drag the four corners onto the multiview grid in the camera picture. */
@Composable
fun CameraSetup(state: EngineState, settings: Settings, onDismiss: () -> Unit) {
    var frame by remember { mutableStateOf(Engine.cameraFrame) }
    var color by remember { mutableStateOf(Engine.cameraColor) }
    DisposableEffect(Unit) {
        Engine.wantCameraColor = true
        onDispose { Engine.wantCameraColor = false }
    }
    LaunchedEffect(Unit) { while (true) { frame = Engine.cameraFrame; color = Engine.cameraColor; delay(300) } }
    // Follows the saved corners (so an automatic find shows up right away).
    val corners = remember(settings.gridCorners) { mutableStateListOf(*settings.gridCorners.toTypedArray()) }
    var dragging by remember { mutableStateOf(-1) }
    var zoom by remember { mutableFloatStateOf(settings.cameraZoom) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(10.dp),
        containerColor = C.surface2,
        title = { Text("Line up the camera") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Put this phone on a stand facing the TV (standing up or on its side), plugged in, with " +
                    "multiview and the games playing. It finds the TV on its own, and checks again every minute " +
                    "and after a bump. Drag a dot to fine-tune.",
                    color = C.muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { Engine.findTv() }, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("Find the TV automatically") }
                state.cameraFind?.let {
                    Text(it, color = if (it.startsWith("Found")) C.accent else C.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                val f = frame
                if (f == null) {
                    Text("Waiting for the camera…", color = C.muted)
                } else {
                    val c = color?.takeIf { it.width == f.width && it.height == f.height }
                    val bmp = remember(f, c) { (c ?: f.toBitmap()).asImageBitmap() }
                    Canvas(
                        Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        dragging = (0 until 4).minByOrNull { i ->
                                            val dx = corners[2 * i] * size.width - p.x
                                            val dy = corners[2 * i + 1] * size.height - p.y
                                            dx * dx + dy * dy
                                        } ?: -1
                                    },
                                    onDragEnd = { dragging = -1; Engine.setGridCorners(corners.toList()) },
                                ) { change, drag ->
                                    change.consume()
                                    val i = dragging
                                    if (i >= 0) {
                                        corners[2 * i] = (corners[2 * i] + drag.x / size.width).coerceIn(0.0, 1.0)
                                        corners[2 * i + 1] = (corners[2 * i + 1] + drag.y / size.height).coerceIn(0.0, 1.0)
                                    }
                                }
                            },
                    ) {
                        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
                        val pts = (0 until 4).map { Offset((corners[2 * it] * size.width).toFloat(), (corners[2 * it + 1] * size.height).toFloat()) }
                        // The boxes as the app will read them.
                        val hom = Homography((0 until 4).map { corners[2 * it] * size.width to corners[2 * it + 1] * size.height })
                        for ((box, r) in boxRects(settings.layout)) {
                            val quad = listOf(r[0] to r[1], r[2] to r[1], r[2] to r[3], r[0] to r[3]).map { (x, y) ->
                                val (u, v) = hom.map(x, y); Offset(u.toFloat(), v.toFloat())
                            }
                            val path = Path().apply { moveTo(quad[0].x, quad[0].y); quad.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                            val color = when {
                                state.cameraGame[box] == false -> Color(0xFFF5A524)
                                state.cameraGame[box] == true -> Color(0xFF2BD9A0)
                                else -> Color(0xAAFFFFFF)
                            }
                            val hasSound = state.currentBox == box
                            drawPath(path, color, style = Stroke(width = if (hasSound) 7f else 3f))
                            // Where it found this game's score bug.
                            state.cameraBugs[box]?.let { a ->
                                fun at(fx: Double, fy: Double): Offset {
                                    val (u, v) = hom.map(r[0] + (r[2] - r[0]) * fx, r[1] + (r[3] - r[1]) * fy)
                                    return Offset(u.toFloat(), v.toFloat())
                                }
                                // (The bug was found inside the box's padded area; close enough to show.)
                                val q = listOf(at(a[0], a[1]), at(a[2], a[1]), at(a[2], a[3]), at(a[0], a[3]))
                                val bp = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                                drawPath(bp, Color(0xFFFFE066), style = Stroke(width = 3f))
                            }
                        }
                        pts.forEach { drawCircle(Color(0xFF6CB4FF), radius = 16f, center = it) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Zoom: make the TV fill as much of the picture as you can (it finds the TV again after).",
                    color = C.muted, fontSize = 12.sp)
                Slider(value = zoom, onValueChange = { zoom = it },
                    onValueChangeFinished = { Engine.setCameraZoom(zoom) })
                Text(
                    if (!state.cameraLive) "Not watching yet: the camera runs while the app is open with camera mode on."
                    else "Sound on box ${state.currentBox ?: "?"}" +
                        (state.cameraBox?.let { " (outline last flashed on box $it)" } ?: "") + "\n" +
                        boxRects(settings.layout).keys.joinToString("\n") { b ->
                            val g = state.cameraGame[b]
                            "Box $b: " + when (g) { true -> "game"; false -> "commercial"; null -> "not sure" } +
                                (state.cameraBars[b]?.let { " ($it%)" } ?: "") +
                                (state.cameraText[b]?.let { "\n   reads: $it" } ?: "")
                        },
                    color = C.text, fontSize = 13.sp,
                )
                Text("Green: it sees the game (reads the score bug). Orange: commercial (no score bug for a while). " +
                    "Thick outline: the box with the sound. Yellow: where it found each game's score bug; that only " +
                    "counts once it has matched what it reads or hears a few times.", color = C.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Engine.setGridCorners(corners.toList()); onDismiss() }) { Text("Done") } },
    )
}
