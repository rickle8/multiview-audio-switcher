package com.rickle8.mvswitch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.rickle8.mvswitch.service.PhoneRemote
import kotlinx.coroutines.delay

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** The code to scan with your own phone to control this one without touching it. */
@Composable
fun PhoneRemoteCard() {
    var url by remember { mutableStateOf(PhoneRemote.url()) }
    LaunchedEffect(Unit) { while (true) { url = PhoneRemote.url(); delay(3000) } }
    Column(Modifier.fillMaxWidth()) {
        Text("Control it from your phone", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        val u = url
        if (u == null) {
            Text("Connect this phone to your Wi-Fi, then a code shows up here.", color = C.muted, fontSize = 13.sp)
            return@Column
        }
        Text("Scan this with your own phone's camera and bookmark the page: the same buttons, the boxes and " +
            "what the camera sees, without touching this phone. Both phones need to be on your Wi-Fi.",
            color = C.muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        val bmp = remember(u) { runCatching { qrBitmap(u, 480) }.getOrNull() }
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Code for the phone controls",
                modifier = Modifier.align(Alignment.CenterHorizontally).size(210.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp))
        }
        Text(u, color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
