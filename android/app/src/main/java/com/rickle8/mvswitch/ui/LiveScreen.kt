package com.rickle8.mvswitch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rickle8.mvswitch.core.GameStatus
import com.rickle8.mvswitch.core.Heard
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.service.BoxView
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState
import com.rickle8.mvswitch.service.UpdateState
import com.rickle8.mvswitch.service.Updater
import androidx.compose.ui.platform.LocalContext

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = C.surface),
        border = BorderStroke(1.dp, C.line),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun LiveScreen(
    state: EngineState,
    settings: Settings,
    update: UpdateState,
    modifier: Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGoToGames: () -> Unit,
) {
    var tapped by remember { mutableStateOf<BoxView?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Multiview Audio", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text)
        Text(statusLine(state), color = C.muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (update.available) {
            val ctx = LocalContext.current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(C.blue.copy(alpha = 0.14f))
                    .clickable(enabled = !update.busy) { Updater.install(ctx) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (update.busy) "Downloading update… ${update.progress ?: 0}%"
                    else "Update available: ${update.latest}",
                    color = C.blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (!update.busy) Text("Install", color = C.blue, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }

        ControlPanel(state, settings, onStart, onStop, onGoToGames)

        SectionLabel("Multiview")
        MultiviewGrid(settings.layout, state.boxes, state.currentBox, state.lockedBox) {
            if (state.running) tapped = it else onGoToGames()
        }
        state.outlineAsk?.let { b ->
            // The camera thinks the sound moved (you used the TV remote?). It only asks: you decide.
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(C.blue.copy(alpha = 0.14f)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Camera saw the sound move to box $b. Is it there?", color = C.blue, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { Engine.answerOutline(true) }) { Text("Yes", color = C.blue, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { Engine.answerOutline(false) }) { Text("No", color = C.muted) }
            }
        }
        state.boxes.filter { it.noGame }.forEach {
            Text("Box ${it.box}: ${it.slot?.name?.ifBlank { null } ?: it.slot?.team} isn't on today. Tap it to pick today's game.",
                color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        state.feedError?.let {
            Text("Live scores unavailable right now ($it). Switching still works from the mic.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.running) {
            Text("Scores update every few seconds. Once running, tap a game to move the audio there.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (state.log.isNotEmpty()) {
            SectionLabel("Activity")
            Panel {
                state.log.take(12).forEachIndexed { i, line ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(line.time, color = C.muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Text(line.text, color = if (i == 0) C.text else C.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    tapped?.let { box ->
        val locked = state.lockedBox == box.box
        AlertDialog(
            onDismissRequest = { tapped = null },
            containerColor = C.surface2,
            title = { Text("Box ${box.box}: ${box.slot?.name?.ifBlank { null } ?: "No game set"}") },
            text = {
                Column {
                    Text("Play audio from this game now, or lock it here so nothing switches until you unlock.",
                        color = C.muted)
                    if (state.running && state.currentBox != box.box) {
                        Spacer(Modifier.height(10.dp))
                        Text("Is the sound already coming from this box? Tell the app so it knows where it is.",
                            color = C.muted, fontSize = 13.sp)
                        TextButton(onClick = { Engine.audioIsOn(box.box); tapped = null }) {
                            Text("Sound is already on this box", color = C.accent)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { Engine.switchTo(box.box); tapped = null }) { Text("Play audio") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (locked) Engine.unlock() else Engine.switchTo(box.box, lock = true)
                    tapped = null
                }) { Text(if (locked) "Unlock" else "Lock here") }
            },
        )
    }
}

private fun statusLine(s: EngineState): String = when {
    s.tvWaiting != null && (s.running || s.starting) -> s.tvWaiting
    s.starting -> "Connecting to the TV…"
    !s.running -> "Not running"
    s.waitingForMultiview -> "Waiting for multiview"
    s.autoPaused -> "Paused · YouTube TV isn't on screen"
    s.paused -> "Paused"
    s.held -> "Waiting while you use the remote"
    s.lockedBox != null -> "Locked on box ${s.lockedBox}"
    else -> "Auto switching · audio on box ${s.currentBox ?: "?"}"
}

@Composable
private fun ControlPanel(
    state: EngineState,
    settings: Settings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGoToGames: () -> Unit,
) {
    Panel {
        val noGames = settings.slots.isEmpty()
        if (!state.running) {
            if (noGames) {
                Text("Pick which games are in each multiview box to get started.", color = C.muted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGoToGames, modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("Choose games", fontSize = 16.sp) }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start switching", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Put your phone near the TV. It keeps working with the screen off.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        } else if (state.waitingForMultiview) {
            Text("YouTube TV is opening. Put multiview up on the TV (the Remote tab works), then tap Go.",
                color = C.text, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { Engine.go() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Go", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.surface2, contentColor = C.red)) {
                Text("Cancel")
            }
        } else {
            HearingRow(state)
            Spacer(Modifier.height(14.dp))
            if (state.autoPaused) {
                Text("Paused because YouTube TV isn't on screen. It picks back up on its own once it is, " +
                    "or tap below when multiview is up again.", color = C.muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { Engine.go() }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("I'm back in multiview")
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { Engine.setPaused(!state.paused) },
                    modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.line)) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = C.text)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.paused) "Resume" else "Pause", color = C.text)
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.surface2, contentColor = C.red)) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
            if (state.lockedBox != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { Engine.unlock() }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, C.amber)) {
                    Icon(Icons.Filled.Lock, null, tint = C.amber)
                    Spacer(Modifier.width(6.dp))
                    Text("Locked on box ${state.lockedBox} · tap to unlock", color = C.amber)
                }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = C.red, fontSize = 13.sp)
        }
        if (!noGames) LearnRow(state)
    }
}

@Composable
private fun HearingRow(state: EngineState) {
    val (label, color) = when {
        state.autoPaused -> "Paused while YouTube TV is off screen" to C.muted
        state.starting && !state.micOn -> "Starting…" to C.muted
        !state.micOn -> "Mic off · using live scores only" to C.muted
        state.paused -> "Paused" to C.muted
        state.heard == Heard.AD -> "Hearing a commercial" to C.amber
        state.heard == Heard.GAME -> "Hearing the game" to C.accent
        else -> "Listening…" to C.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            Icon(if (state.micOn) Icons.Filled.Mic else Icons.Filled.MicOff, null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (state.micOn && !state.paused && state.why != null) {
                Text(state.why, color = C.text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.cameraLive) {
                val ads = state.cameraGame.filterValues { it == false }.keys.sorted()
                val games = state.cameraGame.filterValues { it == true }.keys.sorted()
                val shows = state.cameraShow.sorted()
                Text("Camera: " + (if (games.isNotEmpty()) "game on ${games.joinToString()}" else "no score bugs read yet") +
                    (if (shows.isNotEmpty()) " · show on ${shows.joinToString()}" else "") +
                    (if (ads.isNotEmpty()) " · commercial on ${ads.joinToString()}" else ""),
                    color = C.blue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.micOn && state.topSounds.isNotEmpty()) {
                Text(state.topSounds.joinToString(" · "), color = C.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (state.micOn) {
        val pct = state.adPct
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (pct ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if ((pct ?: 0) >= 60) C.amber else C.accent,
            trackColor = C.surface2,
        )
        Text(if (pct == null) "Not enough sound yet" else "Commercial-like sound: $pct%",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LearnRow(state: EngineState) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp), color = C.line)
    Text("Teach it what your TV sounds like", color = C.text, fontWeight = FontWeight.Medium,
        fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    val on = state.running
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { Engine.label(isAd = false) }, enabled = on,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (on) C.accent else C.line)) {
            Text("It's the game", color = if (on) C.accent else C.muted)
        }
        OutlinedButton(onClick = { Engine.label(isAd = true) }, enabled = on,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (on) C.amber else C.line)) {
            Text("It's an ad", color = if (on) C.amber else C.muted)
        }
    }
    val learned = "Learned ${fmtSecs(state.learnedGameSecs)} of game, ${fmtSecs(state.learnedAdSecs)} of ads" +
        (if (state.adsRemembered > 0) ", remembers ${state.adsRemembered} ad${if (state.adsRemembered == 1) "" else "s"}" else "") +
        "."
    val status = when {
        !state.running -> "$learned These work once you tap Start switching."
        state.starting && !state.micOn -> "Getting started…"
        !state.micOn -> "Mic is off, so it can't learn sounds. \"It's an ad\" still skips to another game. " +
            "Check the mic permission and Settings > Listen with the microphone."
        state.labeling != null -> "Recording this as ${state.labeling}…"
        state.usingLearned -> "$learned Using what it learned."
        else -> "$learned Tap \"It's an ad\" during the first couple of commercials (it also skips the ad). " +
            "That teaches it your TV and room. Game sound is learned on its own from live plays."
    }
    Text(status, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

private fun fmtSecs(s: Int) = if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"

private val BOX_H = 112.dp
private val GAP = 10.dp

@Composable
fun MultiviewGrid(layout: Int, boxes: List<BoxView>, current: Int?, locked: Int?, onTap: (BoxView) -> Unit) {
    val b = { n: Int -> boxes.firstOrNull { it.box == n } ?: BoxView(n, null, null, 0.0, false) }
    val tall = BOX_H * 2 + GAP
    when (layout) {
        2 -> Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            BoxCard(b(1), current == 1, locked == 1, Modifier.weight(1f).height(BOX_H), onTap)
            BoxCard(b(2), current == 2, locked == 2, Modifier.weight(1f).height(BOX_H), onTap)
        }
        3 -> Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            BoxCard(b(1), current == 1, locked == 1, Modifier.weight(1f).height(tall), onTap)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
                BoxCard(b(2), current == 2, locked == 2, Modifier.fillMaxWidth().height(BOX_H), onTap)
                BoxCard(b(3), current == 3, locked == 3, Modifier.fillMaxWidth().height(BOX_H), onTap)
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                BoxCard(b(1), current == 1, locked == 1, Modifier.weight(1f).height(BOX_H), onTap)
                BoxCard(b(2), current == 2, locked == 2, Modifier.weight(1f).height(BOX_H), onTap)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                BoxCard(b(3), current == 3, locked == 3, Modifier.weight(1f).height(BOX_H), onTap)
                BoxCard(b(4), current == 4, locked == 4, Modifier.weight(1f).height(BOX_H), onTap)
            }
        }
    }
}

@Composable
private fun BoxCard(v: BoxView, hasAudio: Boolean, locked: Boolean, modifier: Modifier, onTap: (BoxView) -> Unit) {
    val info = v.info
    val (chip, chipColor) = when {
        v.slot == null -> "EMPTY" to C.muted
        v.slot.isOther -> "OTHER" to C.blue
        v.noGame -> "NO GAME" to C.amber
        info == null -> "—" to C.muted
        info.status == GameStatus.FINAL -> "FINAL" to C.muted
        info.status == GameStatus.PREGAME -> "LATER" to C.blue
        info.statusName == "STATUS_HALFTIME" -> "HALFTIME" to C.blue
        v.onBreak || info.status == GameStatus.BREAK -> "BREAK" to C.amber
        info.status == GameStatus.LIVE -> "LIVE" to C.accent
        else -> "—" to C.muted
    }
    Card(
        modifier = modifier.clickable { onTap(v) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (hasAudio) C.accent.copy(alpha = 0.10f) else C.surface),
        border = BorderStroke(if (hasAudio) 2.dp else 1.dp, if (hasAudio) C.accent else C.line),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${v.box}", color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(chip, color = chipColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(chipColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                if (info?.redZone == true) {
                    Spacer(Modifier.width(4.dp))
                    Text("RZ", color = C.red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (locked) Icon(Icons.Filled.Lock, "Locked", tint = C.amber, modifier = Modifier.size(16.dp))
                if (hasAudio) Icon(Icons.Filled.VolumeUp, "Audio", tint = C.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            if (info != null && info.scores.size == 2) {
                info.scores.forEach { (team, pts) ->
                    Row {
                        Text(team, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            modifier = Modifier.weight(1f))
                        Text("$pts", color = C.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            } else {
                Text(v.slot?.let { it.name.ifBlank { it.team } } ?: "Not set", color = C.text,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info?.detail.orEmpty(), color = C.muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (v.slot != null && v.slot.importance > 1.0) Text("★", color = C.amber, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            RatingBar(v.rating)
        }
    }
}

@Composable
private fun RatingBar(rating: Double) {
    val frac = (rating / 2.5).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        frac > 0.6f -> C.red
        frac > 0.3f -> C.amber
        else -> C.blue
    }
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.surface2)) {
        Box(Modifier.fillMaxWidth(frac).height(4.dp).background(if (frac == 0f) Color.Transparent else color))
    }
}
