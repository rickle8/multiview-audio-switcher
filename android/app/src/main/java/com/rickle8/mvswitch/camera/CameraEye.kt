package com.rickle8.mvswitch.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rickle8.mvswitch.core.GrayFrame
import java.util.concurrent.Executors

/**
 * The back camera, pointed at the TV. Hands about two small grayscale frames a
 * second to [onFrame], already turned upright. Runs while [owner] is on screen.
 */
class CameraEye(private val context: Context, private val onFrame: (GrayFrame) -> Unit, private val onError: (String) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastAt = 0L

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { img -> handle(img) }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                onError(e.message ?: "camera failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
    }

    private fun handle(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAt < 480) return
            lastAt = now
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pxStride = plane.pixelStride
            val srcW = img.width
            val srcH = img.height
            // Downscale to about 320 px on the long side.
            val step = maxOf(1, maxOf(srcW, srcH) / 320)
            val w = srcW / step
            val h = srcH / step
            val raw = ByteArray(w * h)
            for (y in 0 until h) {
                val row = y * step * rowStride
                for (x in 0 until w) raw[y * w + x] = buf.get(row + x * step * pxStride)
            }
            // Turn upright, the way the phone is held.
            val frame = when (img.imageInfo.rotationDegrees) {
                90 -> GrayFrame(h, w, ByteArray(w * h) { i -> val ox = i % h; val oy = i / h; raw[(h - 1 - ox) * w + oy] })
                180 -> GrayFrame(w, h, ByteArray(w * h) { i -> raw[w * h - 1 - i] })
                270 -> GrayFrame(h, w, ByteArray(w * h) { i -> val ox = i % h; val oy = i / h; raw[ox * w + (w - 1 - oy)] })
                else -> GrayFrame(w, h, raw)
            }
            onFrame(frame)
        } catch (e: Exception) {
            onError(e.message ?: "camera frame failed")
        } finally {
            img.close()
        }
    }
}
