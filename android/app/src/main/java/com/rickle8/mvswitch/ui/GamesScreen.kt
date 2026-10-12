package com.rickle8.mvswitch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rickle8.mvswitch.core.GameSlot
import com.rickle8.mvswitch.core.GameStatus
import com.rickle8.mvswitch.core.ScheduledGame
import com.rickle8.mvswitch.data.LEAGUES
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.service.Engine

private val POSITIONS = mapOf(
    2 to listOf("left", "right"),
    3 to listOf("big left", "top right", "bottom right"),
    4 to listOf("top left", "top right", "bottom left", "bottom right"),
)

@Composable
fun GamesScreen(settings: Settings, modifier: Modifier) {
    var picking by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Games", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text)
        Text("Match these to how your YouTube TV multiview is laid out.", color = C.muted, fontSize = 14.sp)

        SectionLabel("Layout")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (n in listOf(2, 3, 4)) {
                LayoutOption(n, settings.layout == n, Modifier.weight(1f)) {
                    Engine.update { s -> s.copy(layout = n, slots = s.slots.filterKeys { it <= n }) }
                }
            }
        }

        SectionLabel("Boxes")
        val count = settings.layout
        for (box in 1..count) {
            SlotCard(box, POSITIONS[count]?.getOrNull(box - 1).orEmpty(), settings.slots[box],
                onPick = { picking = box })
            Spacer(Modifier.height(10.dp))
        }
        Text("★ = your main game. It gets priority and the app comes back to it after its breaks.\n" +
            "Your pick (like \"GB -3.5\") makes games that are close against the spread count as close.",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
    }

    picking?.let { box ->
        GamePicker(settings.league,
            onDismiss = { picking = null },
            onPick = { g ->
                Engine.update { s ->
                    val keep = s.slots[box]
                    s.copy(league = g.league, slots = s.slots + (box to GameSlot(
                        league = g.league, team = g.home.ifBlank { g.away }, name = g.shortName,
                        importance = keep?.importance ?: 1.0, pick = "",
                    )))
                }
                picking = null
            })
    }
}

@Composable
private fun LayoutOption(n: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = if (selected) C.accent else C.line
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).border(BorderStroke(if (selected) 2.dp else 1.dp, border), RoundedCornerShape(16.dp))
            .background(if (selected) C.accent.copy(alpha = 0.08f) else C.surface)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val cell = Modifier.background(if (selected) C.accent else C.muted, RoundedCornerShape(3.dp))
        Box(Modifier.size(width = 64.dp, height = 40.dp)) {
            when (n) {
                2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(cell.weight(1f).height(40.dp)); Box(cell.weight(1f).height(40.dp))
                }
                3 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(cell.weight(1.3f).height(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(cell.fillMaxWidth().height(18.5.dp)); Box(cell.fillMaxWidth().height(18.5.dp))
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(cell.weight(1f).height(18.5.dp)); Box(cell.weight(1f).height(18.5.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(cell.weight(1f).height(18.5.dp)); Box(cell.weight(1f).height(18.5.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("$n games", color = if (selected) C.text else C.muted, fontSize = 13.sp)
    }
}

@Composable
private fun SlotCard(box: Int, position: String, slot: GameSlot?, onPick: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(C.surface2, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Text("$box", color = C.text, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onPick)) {
                Text(position.replaceFirstChar { it.uppercase() }, color = C.muted, fontSize = 12.sp)
                Text(slot?.name?.ifBlank { null } ?: slot?.team ?: "Tap to choose a game",
                    color = if (slot == null) C.accent else C.text, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp)
            }
            if (slot != null) {
                val main = slot.importance > 1.0
                IconButton(onClick = {
                    Engine.update { s ->
                        // Only one main game: starring this one un-stars the rest.
                        s.copy(slots = s.slots.mapValues { (b, g) ->
                            g.copy(importance = if (b == box) (if (main) 1.0 else 2.0) else 1.0)
                        })
                    }
                }) {
                    Icon(if (main) Icons.Filled.Star else Icons.Filled.StarBorder, "Main game",
                        tint = if (main) C.amber else C.muted)
                }
                IconButton(onClick = { Engine.update { s -> s.copy(slots = s.slots - box) } }) {
                    Icon(Icons.Filled.Close, "Clear", tint = C.muted)
                }
            }
        }
        if (slot != null) {
            var pick by remember(slot.team) { mutableStateOf(slot.pick) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pick,
                onValueChange = {
                    pick = it.uppercase()
                    Engine.update { s ->
                        s.copy(slots = s.slots + (box to (s.slots[box] ?: slot).copy(pick = pick)))
                    }
                },
                singleLine = true,
                placeholder = { Text("Your pick (optional), e.g. ${slot.team} -3.5", color = C.muted) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun GamePicker(startLeague: String, onDismiss: () -> Unit, onPick: (ScheduledGame) -> Unit) {
    var league by remember { mutableStateOf(startLeague) }
    var games by remember { mutableStateOf<List<ScheduledGame>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(league) {
        games = null
        error = null
        try {
            games = Engine.schedule(league)
        } catch (e: Exception) {
            error = "Couldn't load games (${e.message}). Check your internet."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface2,
        title = { Text("Choose a game") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in LEAGUES) {
                        FilterChip(
                            selected = league == key,
                            onClick = { league = key },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = C.accent, selectedLabelColor = C.onAccent,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                    val list = games
                    when {
                        error != null -> Text(error!!, color = C.red)
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text("No games today in this league.", color = C.muted)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list) { g ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(C.surface, RoundedCornerShape(12.dp))
                                        .clickable { onPick(g) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(g.shortName, color = C.text, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f))
                                    val live = g.status == GameStatus.LIVE || g.status == GameStatus.BREAK
                                    Text(g.detail, color = if (live) C.accent else C.muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
