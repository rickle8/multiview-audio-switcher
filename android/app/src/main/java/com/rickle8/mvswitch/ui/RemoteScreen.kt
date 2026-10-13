package com.rickle8.mvswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rickle8.mvswitch.core.Key
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.data.TvType
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState

@Composable
private fun RoundKey(icon: ImageVector?, label: String, key: Key, size: Dp = 64.dp, accent: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (accent) C.accent else C.surface2)
            .border(1.dp, C.line, CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                Engine.press(key)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = if (accent) C.onAccent else C.text, modifier = Modifier.size(28.dp))
        else Text(label, color = if (accent) C.onAccent else C.text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RemoteScreen(state: EngineState, settings: Settings, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Remote", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text,
            modifier = Modifier.fillMaxWidth())
        Text("In multiview, the arrows move the audio between games.", color = C.muted, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        if (settings.tvType != TvType.TEST) {
            Button(onClick = { Engine.openYouTubeTv() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Tv, null)
                Spacer(Modifier.width(8.dp))
                Text("Open YouTube TV", fontSize = 16.sp)
            }
        }
        state.remoteMsg?.let {
            Text(it, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))

        // D-pad
        RoundKey(Icons.Filled.KeyboardArrowUp, "Up", Key.UP, 72.dp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundKey(Icons.Filled.KeyboardArrowLeft, "Left", Key.LEFT, 72.dp)
            RoundKey(null, "OK", Key.SELECT, 80.dp, accent = true)
            RoundKey(Icons.Filled.KeyboardArrowRight, "Right", Key.RIGHT, 72.dp)
        }
        Spacer(Modifier.height(10.dp))
        RoundKey(Icons.Filled.KeyboardArrowDown, "Down", Key.DOWN, 72.dp)

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoundKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", Key.BACK)
            RoundKey(Icons.Filled.Home, "Home", Key.HOME)
            RoundKey(Icons.Filled.PlayArrow, "Play", Key.PLAY)
            RoundKey(Icons.Filled.Pause, "Pause", Key.PAUSE)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoundKey(Icons.Filled.Remove, "Volume down", Key.VOL_DOWN)
            RoundKey(Icons.AutoMirrored.Filled.VolumeOff, "Mute", Key.MUTE)
            RoundKey(Icons.Filled.Add, "Volume up", Key.VOL_UP)
        }
        Spacer(Modifier.height(24.dp))
    }
}
