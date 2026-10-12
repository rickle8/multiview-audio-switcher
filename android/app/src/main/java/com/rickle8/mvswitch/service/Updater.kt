package com.rickle8.mvswitch.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateState(
    val installed: String = "",
    val latest: String? = null,
    val available: Boolean = false,
    val busy: Boolean = false,
    val progress: Int? = null,
    val message: String? = null,
)

/** Checks this app's GitHub releases and installs newer builds. */
object Updater {
    private const val REPO = "rickle8/multiview-audio-switcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    private var assetUrl: String? = null

    private fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    private fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun request(url: String, token: String, accept: String) = Request.Builder().url(url)
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
        .build()

    /** quiet = automatic check on app open: no error messages if it can't check. */
    fun check(ctx: Context, quiet: Boolean = false) {
        val app = ctx.applicationContext
        val token = Engine.settings.value.githubToken
        _state.value = _state.value.copy(installed = installedName(app), busy = !quiet,
            message = if (quiet) _state.value.message else "Checking…")
        if (quiet && token.isBlank()) {
            _state.value = _state.value.copy(busy = false)
            return
        }
        scope.launch {
            try {
                val req = request("https://api.github.com/repos/$REPO/releases/latest", token,
                    "application/vnd.github+json")
                val json = http.newCall(req).execute().use { r ->
                    when (r.code) {
                        200 -> JSONObject(r.body!!.string())
                        401 -> throw Exception("GitHub token was rejected. Paste a new one.")
                        404 -> throw Exception(
                            if (token.isBlank()) "Your repo is private. Add a GitHub token below first."
                            else "Can't see the repo with this token. Give it read access to $REPO.")
                        else -> throw Exception("GitHub returned ${r.code}")
                    }
                }
                val tag = json.optString("tag_name")
                val latestCode = tag.substringAfterLast('.').toLongOrNull() ?: 0
                val assets = json.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("url")
                }
                assetUrl = url
                val available = latestCode > installedCode(app) && url != null
                _state.value = _state.value.copy(
                    latest = tag.removePrefix("v"), available = available, busy = false,
                    message = if (available) "Version ${tag.removePrefix("v")} is ready to install."
                    else "You're up to date.",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false,
                    message = if (quiet) null else (e.message ?: "Couldn't check for updates"))
            }
        }
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val url = assetUrl ?: return check(app)
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(
                message = "Allow Multiview Audio to install updates, then tap Install again.")
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        _state.value = _state.value.copy(busy = true, progress = 0, message = "Downloading…")
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "MultiviewAudio.apk")
                // GitHub redirects to a download link; OkHttp drops the token on that hop.
                val req = request(url, Engine.settings.value.githubToken, "application/octet-stream")
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("Download failed (${r.code})")
                    val body = r.body!!
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    _state.value = _state.value.copy(progress = (done * 100 / total).toInt())
                                }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                app.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                _state.value = _state.value.copy(busy = false, progress = null,
                    message = "Tap Update on the installer screen.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, progress = null,
                    message = e.message ?: "Download failed")
            }
        }
    }
}
