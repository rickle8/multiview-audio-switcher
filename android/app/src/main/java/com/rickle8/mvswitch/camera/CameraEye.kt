package com.rickle8.mvswitch.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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
 * The back camera, pointed at the TV. About four frames a second, turned upright
 * by the phone's own sense of which way is up (so it works standing tall or on its
 * side, even with auto-rotate off), as a small grayscale picture for reading and,
 * when asked, a color picture for the setup screen and the phone page.
 *
 * Set up for a sharp picture of a bright screen across a room: 4K when the phone allows it
 * (each box gets about twice the detail of 1080p), at 15 frames a second (less heat over a
 * long game, and longer exposures that don't band on the TV), the phone's best noise
 * reduction and sharpening, focus and exposure on the TV, not the room.
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
    /** Yard lines found in a box (each box gets a close look about every 2 s). */
    private val onLines: (Int, com.rickle8.mvswitch.core.FieldLines.Found) -> Unit = { _, _ -> },
) {
    private val ocr = TileOcr(context.filesDir,
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?",
        onText, onError, onLines)
    /** The full-resolution buffer handed to the box reader, reused once it's done with it. */
    private var fullBuf: ByteArray? = null
    private var lastColorAt = 0L
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
                    .setResolutionStrategy(ResolutionStrategy(Size(3840, 2160),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                sharpen(builder, p)
                val a = builder.build()
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

    /**
     * The phone's best noise reduction and sharpening (what makes small lettering readable), and
     * 15 frames a second when it offers that. Anything the phone doesn't list is left as it is.
     */
    @android.annotation.SuppressLint("UnsafeOptInUsageError") // Camera2Interop: settled API, still marked experimental
    private fun sharpen(builder: ImageAnalysis.Builder, p: ProcessCameraProvider) {
        runCatching {
            val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull() ?: return
            val chars = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            val ext = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
            val nr = chars.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            if (nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true)
                ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            val edge = chars.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            if (edge?.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) == true)
                ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper in 10..15 }
                ?.maxWithOrNull(compareBy<android.util.Range<Int>>({ it.upper }, { it.lower }))
                ?.let { ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
    }

    /** 0 = wide, 1 = fully zoomed in. */
    fun setZoom(linear: Float) {
        runCatching { camera?.cameraControl?.setLinearZoom(linear.coerceIn(0f, 1f)) }
    }

    /** Exposure compensation in stops (the TV's whites shouldn't blow out). */
    fun setExposure(ev: Double) {
        val cam = camera ?: return
        runCatching {
            val st = cam.cameraInfo.exposureState
            val step = st.exposureCompensationStep.toFloat()
            if (!st.isExposureCompensationSupported || step <= 0f) return
            val range = st.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(Math.round(ev / step).toInt().coerceIn(range.lower, range.upper))
        }
    }

    /**
     * Measure light and focus on the TV ([cx], [cy] = its center, [size] = its size, as fractions
     * of the upright picture), so the screen is sharp and exposed right however bright the room is.
     */
    fun meterOn(cx: Double, cy: Double, size: Double = 0.35) {
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
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, a)
                .createPoint(bx.toFloat(), by.toFloat(), size.coerceIn(0.1, 0.9).toFloat())
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
            val greens = if (g != null) runCatching { fieldGreens(img, rotDeg, g.first, g.second) }.getOrNull() else null
            if (greens != null) runCatching { onField(greens.mapValues { (_, cells) -> fieldShare(cells) }) }
            onFrame(upright(raw, w, h, rotDeg), rotDeg)
            // Every half second, hand one box at full resolution to the box reader (yard lines, text).
            if (g != null && now - lastOcrAt >= 450 && ocr.ready()) try {
                lastOcrAt = now
                val full = fullBuf?.takeIf { it.size == srcW * srcH } ?: ByteArray(srcW * srcH).also { fullBuf = it }
                if (pxStride == 1) {
                    for (yy in 0 until srcH) { buf.position(yy * rowStride); buf.get(full, yy * srcW, srcW) }
                    buf.rewind()
                } else {
                    for (yy in 0 until srcH) for (x in 0 until srcW) full[yy * srcW + x] = buf.get(yy * rowStride + x * pxStride)
                }
                ocr.submit(GrayFrame(srcW, srcH, full), rotDeg, g.first, g.second, greens)
            } catch (t: Throwable) {
                buf.rewind()
                onError("text reading: ${t.message ?: t.javaClass.simpleName}")
            }
            // In color, twice the gray picture's detail, twice a second, while a screen is showing it.
            if (wantColor() && now - lastColorAt >= 450) {
                lastColorAt = now
                val cs = maxOf(1, (maxOf(srcW, srcH) + 1279) / 1280)
                onColor(colorBitmap(img, cs, srcW / cs, srcH / cs, rotDeg))
            }
        } catch (e: Throwable) {
            onError(e.message ?: "camera frame failed")
        } finally {
            img.close()
        }
    }

    /**
     * Per box, which cells of a [FieldLines.GW] x [FieldLines.GH] grid over it are football-field
     * green (from the color planes). The box reader uses it to look for yard lines on the field.
     */
    private fun fieldGreens(img: ImageProxy, deg: Int, corners: List<Pair<Double, Double>>, layout: Int): Map<Int, BooleanArray> {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val w = img.width; val h = img.height
        val hom = com.rickle8.mvswitch.core.Homography(corners)
        val gw = com.rickle8.mvswitch.core.FieldLines.GW; val gh = com.rickle8.mvswitch.core.FieldLines.GH
        val out = HashMap<Int, BooleanArray>()
        for ((box, r) in com.rickle8.mvswitch.core.boxRects(layout)) {
            out[box] = com.rickle8.mvswitch.core.FieldLines.greenGrid { i, j ->
                val (u, v) = hom.map(r[0] + (r[2] - r[0]) * (i + 0.5) / gw, r[1] + (r[3] - r[1]) * (j + 0.5) / gh)
                val (rx, ry) = com.rickle8.mvswitch.core.uprightToRaw(u, v, deg)
                val x = (rx * w).toInt().coerceIn(0, w - 1); val y = (ry * h).toInt().coerceIn(0, h - 1)
                val yy = yb.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF
                val uu = ub.get((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride).toInt() and 0xFF
                val vv = vb.get((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride).toInt() and 0xFF
                com.rickle8.mvswitch.core.FieldWatch.isFieldGreen(yy, uu, vv)
            }
        }
        return out
    }

    /** The share of a box that's field green, clear of its edges (the outline) and its bottom fifth (the score bar). */
    private fun fieldShare(cells: BooleanArray): Double {
        val gw = com.rickle8.mvswitch.core.FieldLines.GW; val gh = com.rickle8.mvswitch.core.FieldLines.GH
        var green = 0; var n = 0
        for (j in (gh * 0.06).toInt() until (gh * 0.8).toInt()) for (i in (gw * 0.06).toInt() until (gw * 0.94).toInt()) {
            n++; if (cells[j * gw + i]) green++
        }
        return if (n == 0) 0.0 else green.toDouble() / n
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
