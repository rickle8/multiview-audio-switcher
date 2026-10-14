package com.rickle8.mvswitch.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.rickle8.mvswitch.data.Settings
import com.rickle8.mvswitch.remote.SmartThings
import com.rickle8.mvswitch.service.Engine
import com.rickle8.mvswitch.service.EngineState

/** Settings panel: connect SmartThings so the HDMI buttons work on any Samsung. */
@Composable
fun SmartThingsPanel(state: EngineState, settings: Settings) {
    var signingIn by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(!settings.smartThingsOn) }
    var id by remember(settings.stClientId) { mutableStateOf(settings.stClientId) }
    var secret by remember(settings.stClientSecret) { mutableStateOf(settings.stClientSecret) }
    var pasted by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current

    Panel {
        Text("HDMI buttons", color = C.text, fontWeight = FontWeight.Medium)
        if (settings.smartThingsOn) {
            Text("Switching inputs through SmartThings on ${settings.stTvName}." +
                (settings.stInputNames.takeIf { it.isNotEmpty() }?.let { m ->
                    "\n" + m.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key}: ${it.value}" }
                } ?: ""),
                color = C.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row {
                TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide setup" else "Setup", color = C.muted) }
                TextButton(onClick = { Engine.stDisconnect() }) { Text("Disconnect", color = C.red) }
            }
        } else {
            Text("Many Samsung TVs ignore input keys sent over Wi-Fi. SmartThings can switch them on any recent " +
                "Samsung. It's free and takes about 5 minutes, once, on a computer.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (showSetup) {
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    "1. On a computer, go to developer.smartthings.com and sign in with the Samsung " +
                        "account your TV is on in the SmartThings app.\n" +
                        "2. Create Project > Automation for SmartThings. Name it anything.\n" +
                        "3. In the project, open its OAuth settings (Automation > OAuth2, or Register App).\n" +
                        "4. Scopes: r:devices:*  w:devices:*  x:devices:*\n" +
                        "5. Redirect URI: ${SmartThings.REDIRECT}\n" +
                        "6. Save. Copy the Client ID and Client Secret it shows into the boxes below " +
                        "(the secret is only shown once).",
                    color = C.text, fontSize = 13.sp,
                )
            }
            TextButton(onClick = { clip.setText(AnnotatedString(SmartThings.REDIRECT)) }) {
                Text("Copy the redirect URI", color = C.blue)
            }
            OutlinedTextField(value = id, onValueChange = { id = it.trim() }, singleLine = true,
                label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = secret, onValueChange = { secret = it.trim() }, singleLine = true,
                label = { Text("Client Secret") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = id.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    Engine.update { it.copy(stClientId = id, stClientSecret = secret) }
                    signingIn = true
                },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
            ) { Text("Sign in to SmartThings") }
        }
        state.stStatus?.let {
            Text(it, color = if (it.startsWith("Connected")) C.accent else C.muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }

    if (signingIn) {
        AlertDialog(
            onDismissRequest = { signingIn = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(12.dp),
            title = { Text("Sign in to SmartThings") },
            text = {
                Column {
                    SignInWeb(Engine.stAuthorizeUrl()) { url ->
                        signingIn = false
                        Engine.stSignIn(url)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("If the page gets stuck after you approve, copy its address and paste it here:",
                        color = C.muted, fontSize = 12.sp)
                    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, singleLine = true,
                        label = { Text("Address or code") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = pasted.isNotBlank(), onClick = {
                    signingIn = false
                    Engine.stSignIn(pasted)
                    pasted = ""
                }) { Text("Use it") }
            },
            dismissButton = { TextButton(onClick = { signingIn = false }) { Text("Cancel") } },
        )
    }
}

/** The SmartThings sign-in page; hands back the redirect (with the code) instead of loading it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWeb(url: String, onDone: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(460.dp),
        factory = { ctx ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun check(u: String): Boolean {
                        if (!u.startsWith(SmartThings.REDIRECT)) return false
                        if (!done) { done = true; onDone(u) }
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        check(request.url.toString())
                    override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                        if (check(u)) view.stopLoading()
                    }
                }
                loadUrl(url)
            }
        },
    )
}
