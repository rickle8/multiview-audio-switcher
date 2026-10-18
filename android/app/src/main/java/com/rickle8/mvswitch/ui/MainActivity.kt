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
import androidx.compose.material.icons.filled.SettingsRemote
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
    private var eye: com.rickle8.mvswitch.camera.CameraEye? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera()
    }

    /** Camera mode: watch the TV while the app is open, and keep the screen on. */
    private fun syncCamera(want: Boolean) {
        if (want) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (eye != null) return
            if (checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            eye?.stop(); eye = null
            Engine.cameraControls = null
        }
    }

    private fun startCamera() {
        if (eye != null) return
        val e = com.rickle8.mvswitch.camera.CameraEye(this,
            onFrame = Engine::onCameraFrame,
            onColor = Engine::onCameraColor,
            wantColor = { Engine.wantCameraColor },
            onError = { msg -> Engine.cameraError(msg) },
            grid = { Engine.cameraGrid() },
            onText = Engine::onTileText,
            onBump = { Engine.cameraBumped() })
        eye = e
        e.start(this)
        e.setZoom(Engine.settings.value.cameraZoom)
        Engine.cameraControls = object : Engine.CameraControls {
            override fun zoom(linear: Float) = e.setZoom(linear)
            override fun meterOn(cx: Double, cy: Double) = runOnUiThread { e.meterOn(cx, cy) }
        }
    }

    /** True while the Remote tab is showing: phone volume buttons then control the TV. */
    @Volatile private var remoteTabOpen = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (remoteTabOpen && Engine.settings.value.volumeButtons &&
            Engine.settings.value.tvType != com.rickle8.mvswitch.data.TvType.TEST
        ) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { Engine.press(com.rickle8.mvswitch.core.Key.VOL_UP); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { Engine.press(com.rickle8.mvswitch.core.Key.VOL_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onStart() {
        super.onStart()
        Engine.visible = true
    }

    override fun onStop() {
        Engine.visible = false
        super.onStop()
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        SwitcherService.start(this)
        askBatteryOnce()
    }

    /** Long Sundays: ask once to be left alone by battery optimization so Android never kills it. */
    private fun askBatteryOnce() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Engine.settings.value.askedBattery) return
        Engine.update { it.copy(askedBattery = true) }
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"),
            ))
        }
    }

    private fun startSwitching() {
        val wanted = buildList {
            if (Engine.settings.value.useMic) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) { SwitcherService.start(this); askBatteryOnce() } else permissions.launch(wanted.toTypedArray())
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
                androidx.compose.runtime.LaunchedEffect(settings.useCamera) { syncCamera(settings.useCamera) }
                var tab by rememberSaveable { mutableIntStateOf(if (settings.slots.isEmpty()) 1 else 0) }
                androidx.compose.runtime.SideEffect { remoteTabOpen = tab == 3 }
                Scaffold(
                    containerColor = C.bg,
                    bottomBar = {
                        NavigationBar(containerColor = C.surface) {
                            val items = listOf(
                                Triple("Live", Icons.Filled.SportsFootball, 0),
                                Triple("Games", Icons.Filled.GridView, 1),
                                Triple("Remote", Icons.Filled.SettingsRemote, 3),
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
                        3 -> RemoteScreen(state, settings, mod)
                        else -> SettingsScreen(state, settings, update, mod)
                    }
                }
            }
        }
    }
}
