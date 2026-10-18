package com.rickle8.mvswitch.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rickle8.mvswitch.core.GrayFrame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The back camera, pointed at the TV. About three frames a second, turned upright
 * by the phone's own sense of which way is up (so it works standing tall or on its
 * side, even with auto-rotate off), as a small grayscale picture for reading and,
 * when asked, a color picture for the setup screen.
 */
class CameraEye(
    private val context: Context,
    private val onFrame: (GrayFrame, Int) -> Unit,
    private val onColor: (Bitmap) -> Unit,
    private val wantColor: () -> Boolean,
    private val onError: (String) -> Unit,
    /** Where the grid is (fractions of the upright picture) and the layout, for reading each box's text. */
    private val grid: () -> Pair<List<Pair<Double, Double>>, Int>?,
    private val onText: (Int, List<com.rickle8.mvswitch.core.TileLine>) -> Unit,
    /** The phone got bumped or shaken (so the TV may have moved in the picture). */
    private val onBump: () -> Unit = {},
    /** Per box, the share of the picture that's football-field green, every frame once the TV is lined up. */
    private val onField: (Map<Int, Double>) -> Unit = {},
) {
    private val ocr = TileOcr(context.filesDir,
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?",
        onText, onError)
    private var lastOcrAt = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var lastAt = 0L
    @Volatile private var rotation = Surface.ROTATION_0
    private var bufW = 0
    private var bufH = 0
    private var rotDeg = 0

    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            // Phone tilted past 45°: treat it as turned (with a little stickiness so it doesn't flicker).
            val r = when (deg) {
                in 50..130 -> Surface.ROTATION_270
                in 140..220 -> Surface.ROTATION_180
                in 230..310 -> Surface.ROTATION_90
                in 0..40, in 320..359 -> Surface.ROTATION_0
                else -> return
            }
            if (r != rotation) { rotation = r; analysis?.targetRotation = r }
        }
    }

    // Bumps: a jolt on the motion sensor, beyond the phone's own stillness on its stand.
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    private val linear = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
    private val accel = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    private val gravity = FloatArray(3)
    private var gravitySet = false
    private var lastBump = 0L
    private val motion = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            var x = e.values[0]; var y = e.values[1]; var z = e.values[2]
            if (e.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                // No linear sensor: take gravity out by hand (a slow average of the reading).
                if (!gravitySet) { gravity[0] = x; gravity[1] = y; gravity[2] = z; gravitySet = true }
                for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * e.values[i]
                x -= gravity[0]; y -= gravity[1]; z -= gravity[2]
            }
            if (x * x + y * y + z * z > 1.2f * 1.2f) {
                val t = System.currentTimeMillis()
                if (t - lastBump > 1000) { lastBump = t; runCatching { onBump() } }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    fun start(owner: LifecycleOwner) {
        if (orientation.canDetectOrientation()) orientation.enable()
        runCatching {
            (linear ?: accel)?.let { sensors?.registerListener(motion, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val a = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                a.setAnalyzer(executor) { img -> handle(img) }
                analysis = a
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, a)
                camera = cam
                // A TV is far brighter than the room: expose for the screen, not the room, so the picture
                // isn't blown out.
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                val step = cam.cameraInfo.exposureState.exposureCompensationStep.toFloat()
                if (range.contains(0) && step > 0f) {
                    cam.cameraControl.setExposureCompensationIndex(maxOf(range.lower, (-1f / step).toInt()))
                }
            } catch (e: Exception) {
                onError(e.message ?: "camera failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        orientation.disable()
        runCatching { sensors?.unregisterListener(motion) }
        ocr.close()
        runCatching { provider?.unbindAll() }
        provider = null; camera = null; analysis = null
    }

    /** 0 = wide, 1 = fully zoomed in. */
    fun setZoom(linear: Float) {
        runCatching { camera?.cameraControl?.setLinearZoom(linear.coerceIn(0f, 1f)) }
    }

    /**
     * Measure light and focus on the TV ([cx], [cy] = grid center as fractions of the
     * upright picture), so the screen is exposed right however bright the room is.
     */
    fun meterOn(cx: Double, cy: Double) {
        val cam = camera ?: return
        val a = analysis ?: return
        if (bufW == 0) return
        // Back from the upright picture to the camera's own buffer.
        val (bx, by) = when (rotDeg) {
            90 -> cy to (1 - cx)
            180 -> (1 - cx) to (1 - cy)
            270 -> (1 - cy) to cx
            else -> cx to cy
        }
        runCatching {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, a).createPoint(bx.toFloat(), by.toFloat(), 0.35f)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(1, TimeUnit.DAYS).build())
        }
    }

    private fun handle(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAt < 240) return
            lastAt = now
            val y = img.planes[0]
            val buf = y.buffer
            val rowStride = y.rowStride
            val pxStride = y.pixelStride
            val srcW = img.width
            val srcH = img.height
            bufW = srcW; bufH = srcH
            rotDeg = img.imageInfo.rotationDegrees
            // About 640 px on the long side: sharp enough to read, light enough to keep up.
            val step = maxOf(1, maxOf(srcW, srcH) / 640)
            val w = srcW / step
            val h = srcH / step
            val raw = ByteArray(w * h)
            for (yy in 0 until h) {
                val row = yy * step * rowStride
                for (x in 0 until w) raw[yy * w + x] = buf.get(row + x * step * pxStride)
            }
            val g = runCatching { grid() }.getOrNull()
            // The field's green in each box (from the color planes), before the frame is read.
            if (g != null) runCatching { onField(fieldShares(img, rotDeg, g.first, g.second)) }
            onFrame(upright(raw, w, h, rotDeg), rotDeg)
            // Every half second, hand one box at full resolution to the text reader.
            if (g != null && now - lastOcrAt >= 450 && ocr.ready()) try {
                lastOcrAt = now
                val full = ByteArray(srcW * srcH)
                if (pxStride == 1) {
                    for (yy in 0 until srcH) { buf.position(yy * rowStride); buf.get(full, yy * srcW, srcW) }
                    buf.rewind()
                } else {
                    for (yy in 0 until srcH) for (x in 0 until srcW) full[yy * srcW + x] = buf.get(yy * rowStride + x * pxStride)
                }
                ocr.submit(GrayFrame(srcW, srcH, full), rotDeg, g.first, g.second)
            } catch (t: Throwable) {
                buf.rewind()
                onError("text reading: ${t.message ?: t.javaClass.simpleName}")
            }
            if (wantColor()) onColor(colorBitmap(img, step, w, h, rotDeg))
        } catch (e: Throwable) {
            onError(e.message ?: "camera frame failed")
        } finally {
            img.close()
        }
    }

    /**
     * Per box, the share of sampled points that are football-field green. Samples a 24 x 14 grid
     * inside each box, clear of its edges (the outline) and its bottom fifth (the score bar).
     */
    private fun fieldShares(img: ImageProxy, deg: Int, corners: List<Pair<Double, Double>>, layout: Int): Map<Int, Double> {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val w = img.width; val h = img.height
        val hom = com.rickle8.mvswitch.core.Homography(corners)
        val out = HashMap<Int, Double>()
        for ((box, r) in com.rickle8.mvswitch.core.boxRects(layout)) {
            var green = 0; var n = 0
            for (j in 0 until 14) for (i in 0 until 24) {
                val gx = r[0] + (r[2] - r[0]) * (0.06 + 0.88 * (i + 0.5) / 24)
                val gy = r[1] + (r[3] - r[1]) * (0.06 + 0.74 * (j + 0.5) / 14)
                val (u, v) = hom.map(gx, gy)
                val (rx, ry) = com.rickle8.mvswitch.core.uprightToRaw(u, v, deg)
                val x = (rx * w).toInt().coerceIn(0, w - 1); val y = (ry * h).toInt().coerceIn(0, h - 1)
                val yy = yb.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF
                val uu = ub.get((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride).toInt() and 0xFF
                val vv = vb.get((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride).toInt() and 0xFF
                n++
                if (com.rickle8.mvswitch.core.FieldWatch.isFieldGreen(yy, uu, vv)) green++
            }
            out[box] = if (n == 0) 0.0 else green.toDouble() / n
        }
        return out
    }

    private fun upright(raw: ByteArray, w: Int, h: Int, deg: Int): GrayFrame = when (deg) {
        90 -> GrayFrame(h, w, ByteArray(w * h) { i -> val ox = i % h; val oy = i / h; raw[(h - 1 - ox) * w + oy] })
        180 -> GrayFrame(w, h, ByteArray(w * h) { i -> raw[w * h - 1 - i] })
        270 -> GrayFrame(h, w, ByteArray(w * h) { i -> val ox = i % h; val oy = i / h; raw[ox * w + (w - 1 - oy)] })
        else -> GrayFrame(w, h, raw)
    }

    /** The same picture in color, upright, for people to look at. */
    private fun colorBitmap(img: ImageProxy, step: Int, w: Int, h: Int, deg: Int): Bitmap {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val px = IntArray(w * h)
        for (yy in 0 until h) {
            val sy = yy * step
            for (x in 0 until w) {
                val sx = x * step
                val Y = (yb.get(sy * yP.rowStride + sx * yP.pixelStride).toInt() and 0xFF)
                val ci = (sy / 2) * uP.rowStride + (sx / 2) * uP.pixelStride
                val U = (ub.get(ci).toInt() and 0xFF) - 128
                val V = (vb.get((sy / 2) * vP.rowStride + (sx / 2) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (Y + 1.402 * V).toInt().coerceIn(0, 255)
                val g = (Y - 0.344 * U - 0.714 * V).toInt().coerceIn(0, 255)
                val b = (Y + 1.772 * U).toInt().coerceIn(0, 255)
                px[yy * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val base = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (deg == 0) return base
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(base, 0, 0, w, h, m, true)
    }
}
