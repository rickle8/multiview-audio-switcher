package com.rickle8.mvswitch.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.data.Shortcut
import com.rickle8.mvswitch.data.TvType
import com.rickle8.mvswitch.remote.TvApp
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun inputLabel(settings: Settings, n: Int) = settings.inputNames[n]?.takeIf { it.isNotBlank() } ?: "HDMI $n"

/** A stable color per app name, for tiles without an icon. */
private fun tileColor(name: String): Color {
    val palette = listOf(0xFF2BD9A0, 0xFF6CB4FF, 0xFFF5A524, 0xFFFF6B6B, 0xFFB18CFF, 0xFF4DD0E1, 0xFFFF8A65)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

/** How a taught step reads on screen: "SOURCE" -> "Source", "LEFT*8" -> "◀×8". */
private fun stepLabel(step: String): String {
    val parts = step.split('*')
    val name = when (parts[0]) {
        "SOURCE" -> "Source"; "LEFT" -> "◀"; "RIGHT" -> "▶"; "UP" -> "▲"; "DOWN" -> "▼"
        "SELECT" -> "OK"; "BACK" -> "Back"; "HOME" -> "Home"
        else -> parts[0].lowercase()
    }
    return if (parts.size > 1) "$name×${parts[1]}" else name
}

@Composable
private fun KeyButton(icon: ImageVector?, label: String, key: Key? = null, size: Dp = 56.dp,
                      bg: Color = C.surface2, tint: Color = C.text, onClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, C.line, CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (onClick != null) onClick() else key?.let { Engine.press(it) }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.45f))
        else Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillButton(text: String, modifier: Modifier = Modifier, bg: Color = C.surface2,
                       onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, C.line, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                onLongClick = onLongClick?.let { l -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); l() } },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = C.text, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Connected / connecting / not connected, for Samsung TVs (Rokus don't keep a connection). */
@Composable
private fun TvStatus(state: EngineState, settings: Settings) {
    if (settings.tvType != TvType.SAMSUNG) return
    val (text, color) = when {
        settings.tvHost.isBlank() -> "Add your TV in Settings" to C.amber
        state.tvWaiting != null -> state.tvWaiting to C.amber
        state.tvLinked == true -> "Connected to the TV" to C.accent
        state.remoteMsg != null -> "Not connected · tap to try again" to C.red
        else -> "Connecting…" to C.muted
    }
    Row(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { Engine.warmUp() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(state: EngineState, settings: Settings, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    var loadingApps by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Shortcut?>(null) }
    var editingInput by remember { mutableStateOf<Int?>(null) }
    var teaching by remember { mutableStateOf<Int?>(null) }
    var swipeMode by rememberSaveable { mutableStateOf(false) }
    val isTest = settings.tvType == TvType.TEST
    val isSamsung = settings.tvType == TvType.SAMSUNG

    fun loadApps(refresh: Boolean) {
        if (loadingApps) return
        loadingApps = true
        scope.launch { apps = Engine.apps(refresh); loadingApps = false }
    }
    LaunchedEffect(settings.tvType, settings.tvHost) {
        apps = null
        if (!isTest && settings.tvHost.isNotBlank()) loadApps(false)
    }
    // Keep the TV connection ready while this tab is open, so taps go through instantly.
    LaunchedEffect(settings.tvType, settings.tvHost) {
        while (true) {
            Engine.warmUp()
            delay(15_000)
        }
    }
    /** An input tap: teach it first if this TV ignores input keys. */
    fun tapInput(n: Int) {
        if (Engine.needsTeaching(n)) teaching = n else Engine.switchInput(n)
    }

    Column(Modifier.then(modifier).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remote", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text)
                Text(if (settings.volumeButtons) "Your phone's volume buttons control the TV here."
                else "In multiview, the arrows move the audio.", color = C.muted, fontSize = 13.sp)
            }
            KeyButton(Icons.Filled.PowerSettingsNew, "Power", size = 52.dp, bg = C.red.copy(alpha = 0.18f),
                tint = C.red) { Engine.power() }
        }
        TvStatus(state, settings)
        state.remoteMsg?.let { Text(it, color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (state.running && (state.waitingForMultiview || state.autoPaused)) {
            Panel(Modifier.padding(top = 10.dp)) {
                Text(
                    if (state.waitingForMultiview) "Put multiview up on the TV, then tap Go to start switching."
                    else "Auto switching is paused while YouTube TV isn't on screen.",
                    color = C.text, fontSize = 14.sp,
                )
                TextButton(onClick = { Engine.go() }) {
                    Text(if (state.waitingForMultiview) "Go" else "I'm back in multiview", color = C.accent,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        } else if (state.held) {
            Text("Auto switching is waiting while you use the remote.", color = C.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }

        // ------------------------------------------------ shortcuts
        SectionLabel("Shortcuts")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.shortcuts.forEach { s ->
                PillButton(s.label, bg = if (s.kind == Shortcut.INPUT) C.blue.copy(alpha = 0.16f) else C.surface2,
                    onLongClick = { removing = s }) {
                    val n = s.value.toIntOrNull()
                    if (s.kind == Shortcut.INPUT && n != null) tapInput(n) else Engine.runShortcut(s)
                }
            }
            PillButton("+ Add", bg = C.accent.copy(alpha = 0.14f)) {
                adding = true
                if (apps == null) loadApps(false)
            }
        }
        Text("Long-press a shortcut to remove it.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        // ------------------------------------------------ inputs
        SectionLabel("Inputs")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..4) {
                PillButton(inputLabel(settings, n), Modifier.weight(1f), onLongClick = { editingInput = n }) {
                    tapInput(n)
                }
            }
        }
        state.askInput?.let { n ->
            Panel(Modifier.padding(top = 10.dp)) {
                Text("Did the TV switch to ${inputLabel(settings, n)}?", color = C.text, fontWeight = FontWeight.Medium)
                Text("Some Samsung TVs ignore the app's input buttons. If yours did, you can teach it the way " +
                    "you'd do it with the TV remote, once.", color = C.muted, fontSize = 12.sp)
                Row {
                    TextButton(onClick = { Engine.answerInput(worked = true) }) { Text("Yes", color = C.accent) }
                    TextButton(onClick = { Engine.answerInput(worked = false); teaching = n }) {
                        Text("No, teach it", color = C.amber)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { Engine.dismissInputQuestion() }) { Text("Later", color = C.muted) }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Long-press to name one (PS5, PC…)" + if (isSamsung) " or teach it." else ".",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (isSamsung) {
                TextButton(onClick = { Engine.nextInput() }) { Text("Next HDMI", color = C.text) }
                TextButton(onClick = { Engine.press(Key.SOURCE) }) {
                    Icon(Icons.Filled.Input, null, tint = C.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Source", color = C.text)
                }
            }
        }

        // ------------------------------------------------ navigation
        SectionLabel("Navigate")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !swipeMode, onClick = { swipeMode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Buttons") }
            SegmentedButton(selected = swipeMode, onClick = { swipeMode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Swipe pad") }
        }
        Spacer(Modifier.height(16.dp))
        if (swipeMode) SwipePad() else DPad()
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Key.BACK)
            KeyButton(Icons.Filled.Home, "Home", Key.HOME)
            KeyButton(Icons.Filled.Menu, "Menu", Key.MENU)
            KeyButton(Icons.Filled.Info, "Info", Key.INFO)
        }

        // ------------------------------------------------ playback, volume, channel
        SectionLabel("Playback")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton(Icons.Filled.FastRewind, "Rewind", Key.REWIND)
            KeyButton(Icons.Filled.PlayArrow, "Play", Key.PLAY)
            KeyButton(Icons.Filled.Pause, "Pause", Key.PAUSE)
            KeyButton(Icons.Filled.FastForward, "Fast forward", Key.FAST_FORWARD)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Rocker("VOL", Key.VOL_UP, Key.VOL_DOWN)
            KeyButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", Key.MUTE)
            Rocker("CH", Key.CH_UP, Key.CH_DOWN)
        }

        // ------------------------------------------------ typing
        SectionLabel("Type on the TV")
        val focus = LocalFocusManager.current
        val typeFocus = remember { FocusRequester() }
        var text by remember { mutableStateOf("") }
        val send = { if (text.isNotBlank()) { Engine.sendText(text); text = ""; focus.clearFocus() } }
        // The TV opened its keyboard (a search box): jump straight to typing.
        LaunchedEffect(state.tvKeyboard) { if (state.tvKeyboard) runCatching { typeFocus.requestFocus() } }
        if (state.tvKeyboard) {
            Text("The TV is asking for text. Type here and press send.", color = C.accent, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("Open a search box on the TV, then type here") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = { IconButton(onClick = send) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = C.accent) } },
            modifier = Modifier.fillMaxWidth().focusRequester(typeFocus), shape = RoundedCornerShape(12.dp),
        )

        // ------------------------------------------------ all apps
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("All apps on the TV") }
            IconButton(onClick = { loadApps(true) }) { Icon(Icons.Filled.Refresh, "Reload apps", tint = C.muted) }
        }
        when {
            isTest -> Text("Pick your TV in Settings to see its apps.", color = C.muted, fontSize = 13.sp)
            loadingApps && apps == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            apps.isNullOrEmpty() -> Text("Couldn't get the app list. Make sure the TV is on, then tap reload.",
                color = C.muted, fontSize = 13.sp)
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    apps!!.forEach { app ->
                        AppTile(app,
                            onClick = { Engine.launchApp(app.id) },
                            onLongClick = {
                                if (settings.shortcuts.none { it.kind == Shortcut.APP && it.value == app.id }) {
                                    Engine.update { it.copy(shortcuts = it.shortcuts + Shortcut(Shortcut.APP, app.name, app.id)) }
                                }
                            })
                    }
                }
                Text("Tap to open. Long-press to add to Shortcuts.", color = C.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    // ------------------------------------------------ dialogs
    if (adding) AddShortcutDialog(settings, apps, loadingApps, onDismiss = { adding = false }) { s ->
        Engine.update { it.copy(shortcuts = it.shortcuts + s) }
        adding = false
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = C.surface2,
            title = { Text("Remove “${s.label}”?") },
            confirmButton = {
                TextButton(onClick = {
                    Engine.update { it.copy(shortcuts = it.shortcuts - s) }; removing = null
                }) { Text("Remove", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    editingInput?.let { n ->
        InputDialog(settings, n, canTeach = isSamsung, onDismiss = { editingInput = null },
            onTeach = { editingInput = null; teaching = n })
    }
    teaching?.let { n ->
        TeachDialog(settings, n, onDismiss = { teaching = null })
    }
}

/** Name an input, and (Samsung) see or change how it gets there. */
@Composable
private fun InputDialog(settings: Settings, n: Int, canTeach: Boolean, onDismiss: () -> Unit, onTeach: () -> Unit) {
    var name by remember(n) { mutableStateOf(settings.inputNames[n].orEmpty()) }
    val steps = settings.inputSteps[n].orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.surface2,
        title = { Text("HDMI $n") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(14) }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text("PS5, PC, Xbox…") }, shape = RoundedCornerShape(12.dp))
                if (canTeach) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (steps.isEmpty()) "Switches with the TV's input key." +
                            (if (settings.directInputs < 0) " Your TV ignores that, so teach it the steps." else "")
                        else "Switches with the steps you taught it: " + steps.joinToString(" → ") { stepLabel(it) },
                        color = C.muted, fontSize = 13.sp,
                    )
                    Row {
                        TextButton(onClick = onTeach) { Text(if (steps.isEmpty()) "Teach it" else "Teach again", color = C.accent) }
                        if (steps.isNotEmpty()) {
                            TextButton(onClick = { Engine.saveSteps(n, emptyList()) }) { Text("Forget steps", color = C.red) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Engine.update { s ->
                    val names = if (name.isBlank()) s.inputNames - n else s.inputNames + (n to name.trim())
                    // Keep any shortcut for this input in sync with its new name.
                    s.copy(inputNames = names, shortcuts = s.shortcuts.map { sc ->
                        if (sc.kind == Shortcut.INPUT && sc.value == n.toString())
                            sc.copy(label = name.trim().ifBlank { "HDMI $n" }) else sc
                    })
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Teach an input: you switch the TV to it with these buttons (the TV follows
 * along), and the app repeats the same steps from then on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeachDialog(settings: Settings, n: Int, onDismiss: () -> Unit) {
    var steps by remember(n) { mutableStateOf(listOf<String>()) }
    val label = inputLabel(settings, n)
    fun add(step: String) {
        Engine.pressStep(step)
        steps = steps + step
    }
    @Composable
    fun Small(text: String, step: String, wide: Boolean = false) {
        PillButton(text, if (wide) Modifier.width(132.dp) else Modifier.width(60.dp)) { add(step) }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.surface2,
        title = { Text("Teach $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Switch the TV to $label with these buttons, the way you would with the TV remote. " +
                    "The TV follows along, and the app repeats the same steps next time.\n\n" +
                    "Usually: Source, then Start of list, then ▶ until $label is highlighted, then OK.",
                    color = C.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("Source", "SOURCE", wide = true)
                    Small("Start of list", "LEFT*8", wide = true)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Small("▲", "UP")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Small("◀", "LEFT"); Small("OK", "SELECT"); Small("▶", "RIGHT")
                    }
                    Spacer(Modifier.height(6.dp))
                    Small("▼", "DOWN")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("Back", "BACK", wide = true)
                    Small("Home", "HOME", wide = true)
                }
                Spacer(Modifier.height(12.dp))
                Text(if (steps.isEmpty()) "Steps: none yet" else "Steps: " + steps.joinToString(" → ") { stepLabel(it) },
                    color = C.text, fontSize = 13.sp)
                Row {
                    if (steps.isNotEmpty()) {
                        TextButton(onClick = { steps = steps.dropLast(1) }) { Text("Undo last", color = C.muted) }
                        TextButton(onClick = { Engine.testSteps(n, steps) }) { Text("Try it", color = C.blue) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = steps.isNotEmpty(), onClick = { Engine.saveSteps(n, steps); onDismiss() }) {
                Text("Save", color = if (steps.isNotEmpty()) C.accent else C.muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DPad() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        KeyButton(Icons.Filled.KeyboardArrowUp, "Up", Key.UP, 68.dp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(Icons.Filled.KeyboardArrowLeft, "Left", Key.LEFT, 68.dp)
            KeyButton(null, "OK", Key.SELECT, 80.dp, bg = C.accent, tint = C.onAccent)
            KeyButton(Icons.Filled.KeyboardArrowRight, "Right", Key.RIGHT, 68.dp)
        }
        Spacer(Modifier.height(8.dp))
        KeyButton(Icons.Filled.KeyboardArrowDown, "Down", Key.DOWN, 68.dp)
    }
}

/** Swipe in any direction to move (long swipes move several times), tap for OK. */
@Composable
private fun SwipePad() {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)).background(C.surface2)
            .border(1.dp, C.line, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); Engine.press(Key.SELECT)
                })
            }
            .pointerInput(Unit) {
                val step = 70.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(onDragStart = { dx = 0f; dy = 0f }) { change, drag ->
                    change.consume()
                    dx += drag.x; dy += drag.y
                    if (abs(dx) >= step || abs(dy) >= step) {
                        val key = if (abs(dx) > abs(dy)) (if (dx > 0) Key.RIGHT else Key.LEFT)
                        else (if (dy > 0) Key.DOWN else Key.UP)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Engine.press(key)
                        dx = 0f; dy = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Swipe to move · tap for OK", color = C.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Rocker(label: String, up: Key, down: Key) {
    Column(
        Modifier.clip(RoundedCornerShape(28.dp)).background(C.surface2).border(1.dp, C.line, RoundedCornerShape(28.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = { Engine.press(up) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Add, "$label up", tint = C.text)
        }
        Text(label, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = { Engine.press(down) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Remove, "$label down", tint = C.text)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val icon by produceState<android.graphics.Bitmap?>(null, app.id) {
        value = Engine.appIcon(app.id)?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
    }
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(tileColor(app.name).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) Image(bmp.asImageBitmap(), app.name, Modifier.fillMaxSize())
            else Text(app.name.take(1).uppercase(), color = tileColor(app.name), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(app.name, color = C.text, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
    }
}

@Composable
private fun AddShortcutDialog(
    settings: Settings, apps: List<TvApp>?, loading: Boolean,
    onDismiss: () -> Unit, onAdd: (Shortcut) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.surface2,
        title = { Text("Add a shortcut") },
        text = {
            Column {
                Text("Inputs", color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 1..4) {
                        PillButton(inputLabel(settings, n), Modifier.weight(1f)) {
                            onAdd(Shortcut(Shortcut.INPUT, inputLabel(settings, n), n.toString()))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Apps", color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                    when {
                        loading && apps == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        apps.isNullOrEmpty() -> Text("No app list from the TV yet.", color = C.muted)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(apps.filter { a -> settings.shortcuts.none { it.kind == Shortcut.APP && it.value == a.id } }) { app ->
                                Text(app.name, color = C.text, modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp)).background(C.surface)
                                    .clickable { onAdd(Shortcut(Shortcut.APP, app.name, app.id)) }
                                    .padding(12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
