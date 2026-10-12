package com.rickle8.mvswitch.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.SwitcherService
import com.rickle8.mvswitch.service.Updater

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { SwitcherService.start(this) }

    private fun startSwitching() {
        val wanted = buildList {
            if (Engine.settings.value.useMic) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) SwitcherService.start(this) else permissions.launch(wanted.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        if (savedInstanceState == null) Updater.check(this, quiet = true)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val state by Engine.state.collectAsState()
                val settings by Engine.settings.collectAsState()
                val update by Updater.state.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(if (settings.slots.isEmpty()) 1 else 0) }
                Scaffold(
                    containerColor = C.bg,
                    bottomBar = {
                        NavigationBar(containerColor = C.surface) {
                            val items = listOf(
                                Triple("Live", Icons.Filled.SportsFootball, 0),
                                Triple("Games", Icons.Filled.GridView, 1),
                                Triple("Settings", Icons.Filled.Settings, 2),
                            )
                            for ((label, icon, i) in items) {
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = C.onAccent,
                                        indicatorColor = C.accent,
                                        selectedTextColor = C.text,
                                        unselectedIconColor = C.muted,
                                        unselectedTextColor = C.muted,
                                    ),
                                )
                            }
                        }
                    },
                ) { pad ->
                    val mod = Modifier.padding(pad)
                    when (tab) {
                        0 -> LiveScreen(state, settings, update, mod,
                            onStart = ::startSwitching,
                            onStop = { SwitcherService.stop(this) },
                            onGoToGames = { tab = 1 })
                        1 -> GamesScreen(settings, mod)
                        else -> SettingsScreen(state, settings, update, mod)
                    }
                }
            }
        }
    }
}
