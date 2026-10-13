package com.rickle8.mvswitch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rickle8.mvswitch.data.Hopping
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.data.TvType
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState
import com.rickle8.mvswitch.service.UpdateState
import com.rickle8.mvswitch.service.Updater
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: EngineState, settings: Settings, update: UpdateState, modifier: Modifier) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text)

        // ------------------------------------------------------------ TV
        SectionLabel("TV")
        Panel {
            Text("Where YouTube TV is playing", color = C.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TvType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = settings.tvType == t,
                        onClick = { Engine.update { it.copy(tvType = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TvType.entries.size),
                    ) { Text(t.label, fontSize = 13.sp) }
                }
            }
            if (settings.tvType != TvType.TEST) {
                Spacer(Modifier.height(14.dp))
                var host by remember(settings.tvHost) { mutableStateOf(settings.tvHost) }
                val save = {
                    Engine.update { it.copy(tvHost = host.trim(), samsungToken =
                        if (host.trim() == it.tvHost) it.samsungToken else null) }
                    focus.clearFocus()
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("${settings.tvType.label} IP address") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    trailingIcon = {
                        if (host.trim() != settings.tvHost) {
                            IconButton(onClick = { save() }) { Icon(Icons.Filled.Check, "Save", tint = C.accent) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    if (settings.tvType == TvType.SAMSUNG)
                        "On the TV: Settings > General > Network > Network Status > IP Settings. " +
                            "Your phone must be on the same Wi-Fi."
                    else "On the Roku: Settings > Network > About. Also turn on Settings > System > " +
                        "Advanced system settings > Control by mobile apps.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text("Test mode doesn't touch the TV. Use it to see what the app would do.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { Engine.testTv() }, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Tv, null)
                Spacer(Modifier.width(8.dp))
                Text("Test with the TV")
            }
            Text(
                state.testStatus
                    ?: "Open multiview on the TV first. The audio should walk through every box.",
                color = if (state.testStatus?.startsWith("Failed") == true) C.red else C.muted,
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            if (settings.tvType != TvType.TEST) {
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                ToggleRow("Open YouTube TV when starting",
                    "If it isn't already on screen, Start opens it on the TV. Then you put multiview up and tap Go.",
                    settings.openYouTubeTv) { v -> Engine.update { it.copy(openYouTubeTv = v) } }
                Spacer(Modifier.height(12.dp))
                ToggleRow("Pause when YouTube TV isn't on screen",
                    "So it never presses arrows in another app. Resumes when YouTube TV is back; stops after 20 minutes.",
                    settings.autoPause) { v -> Engine.update { it.copy(autoPause = v) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
            ToggleRow("Reset highlight before each switch",
                "Presses up/left first so it never loses track. Turn off if that opens a menu on your TV.",
                settings.homing) { v -> Engine.update { it.copy(homing = v) } }
        }

        // ------------------------------------------------------------ Detection
        SectionLabel("Detecting commercials")
        Panel {
            ToggleRow("Listen with the microphone",
                "Hears when the game you're on goes to commercial. Off = live scores only.",
                settings.useMic) { v -> Engine.update { it.copy(useMic = v) } }
            if (settings.useMic) {
                Spacer(Modifier.height(12.dp))
                var sens by remember(settings.micSensitivity) { mutableFloatStateOf(settings.micSensitivity) }
                Text("Commercial sensitivity", color = C.text, fontWeight = FontWeight.Medium)
                Slider(value = sens, onValueChange = { sens = it },
                    onValueChangeFinished = { Engine.update { it.copy(micSensitivity = sens) } })
                Row {
                    Text("Only obvious ads", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Switch sooner", color = C.muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Learned sounds: ${state.learnedGameSecs}s game, ${state.learnedAdSecs}s ads" +
                    (if (state.usingLearned) " (in use)" else " (needs 25s of each)") +
                    "\nAds it can recognize on replay: ${state.adsRemembered}" +
                    "\nAnnouncer voices: " + (if (state.voiceLearned) "learned" else "still listening"),
                    color = C.muted, fontSize = 12.sp, lineHeight = 18.sp)
                TextButton(onClick = { Engine.resetLearning() }) {
                    Text("Forget everything it learned", color = C.red)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
            ToggleRow("Listen for announcer break phrases",
                "Switches when the announcers say things like \u201cwe'll be right back\u201d or \u201cstay with us.\u201d " +
                    "Runs offline on the phone. Only counts the announcers' voices once it knows them.",
                settings.useCues) { v -> Engine.update { it.copy(useCues = v) } }
            if (settings.useCues) {
                Spacer(Modifier.height(10.dp))
                var text by remember(settings.cuePhrases) { mutableStateOf(settings.cuePhrases.joinToString("\n")) }
                val parsed = text.split("\n", ",").map { it.trim() }.filter { it.isNotBlank() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Phrases (one per line)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 8,
                )
                Row {
                    if (parsed != settings.cuePhrases && parsed.isNotEmpty()) {
                        TextButton(onClick = { Engine.update { it.copy(cuePhrases = parsed) }; focus.clearFocus() }) {
                            Text("Save phrases", color = C.accent)
                        }
                    }
                    TextButton(onClick = {
                        Engine.update { it.copy(cuePhrases = com.rickle8.mvswitch.audio.DEFAULT_CUE_PHRASES) }
                    }) { Text("Reset to defaults", color = C.muted) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
            ToggleRow("Switch early on scores and stoppages",
                "Moves as soon as live data shows a touchdown, field goal, 2-minute warning, end of quarter, " +
                    "injury or halftime, before the ad even starts.",
                settings.proactive) { v -> Engine.update { it.copy(proactive = v) } }
        }

        // ------------------------------------------------------------ Switching
        SectionLabel("Switching")
        Panel {
            Text("Jump to a better game mid-play", color = C.text, fontWeight = FontWeight.Medium)
            Text("How readily it leaves a blowout for a close game, even without a commercial.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Hopping.entries.forEachIndexed { i, h ->
                    SegmentedButton(
                        selected = settings.hopping == h,
                        onClick = { Engine.update { it.copy(hopping = h) } },
                        shape = SegmentedButtonDefaults.itemShape(i, Hopping.entries.size),
                    ) { Text(h.label) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
            var secs by remember(settings.breakSecs) { mutableFloatStateOf(settings.breakSecs.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Typical break length", color = C.text, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("%d:%02d".format(secs.toInt() / 60, secs.toInt() % 60), color = C.accent,
                    fontWeight = FontWeight.SemiBold)
            }
            Slider(value = secs, onValueChange = { secs = it }, valueRange = 90f..240f, steps = 9,
                onValueChangeFinished = { Engine.update { it.copy(breakSecs = secs.toInt()) } })
            Text("When it's safe to go back to a game that went to commercial.",
                color = C.muted, fontSize = 12.sp)
        }

        SectionLabel("Updates")
        Panel {
            val ctx = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Installed: ${update.installed.ifBlank { "?" }}", color = C.text,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                update.latest?.let { Text("Latest: $it", color = C.muted, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (update.available) Updater.install(ctx) else Updater.check(ctx) },
                enabled = !update.busy,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
            ) {
                Text(when {
                    update.busy && update.progress != null -> "Downloading… ${update.progress}%"
                    update.busy -> "Checking…"
                    update.available -> "Install ${update.latest}"
                    else -> "Check for updates"
                })
            }
            update.message?.let {
                Text(it, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
            var token by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("GitHub token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    Engine.update { it.copy(githubToken = token) }; focus.clearFocus(); Updater.check(ctx)
                }),
                trailingIcon = {
                    if (token != settings.githubToken) {
                        IconButton(onClick = {
                            Engine.update { it.copy(githubToken = token) }; focus.clearFocus(); Updater.check(ctx)
                        }) { Icon(Icons.Filled.Check, "Save", tint = C.accent) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Text(
                "Only needed if the repo is private. On GitHub: Settings > Developer settings > " +
                    "Fine-grained tokens > Generate. Repository access: only multiview-audio-switcher. " +
                    "Permissions: Contents, Read-only. Copy it and paste it here.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionLabel("Tips")
        Panel {
            Text(
                "• Set the phone near the TV speaker, away from where people talk.\n" +
                    "• The app keeps running with the screen off. Use the notification to pause or stop.\n" +
                    "• If you move the multiview highlight with the real remote, it catches up on the next switch.\n" +
                    "• Some phones kill background apps. If it stops on its own, set battery usage for " +
                    "Multiview Audio to Unrestricted in Android settings.",
                color = C.muted, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.text, fontWeight = FontWeight.Medium)
            Text(subtitle, color = C.muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.onAccent))
    }
}
