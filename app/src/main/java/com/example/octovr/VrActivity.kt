package com.example.octovr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.*

class VrActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

    // Положение плашки в комнате (X, Y, Z)
    private var panelWorldX by mutableFloatStateOf(0f)
    private var panelWorldY by mutableFloatStateOf(0f)
    private var panelWorldZ by mutableFloatStateOf(1.6f)
    private var isCalibrated = false

    // 3D-Куб в пространстве
    private var cubeWorldX by mutableFloatStateOf(0.45f)
    private var cubeWorldY by mutableFloatStateOf(-0.15f)
    private var cubeWorldZ by mutableFloatStateOf(1.3f)
    private var isCubeHovered by mutableStateOf(false)
    private var isCubeGrabbed by mutableStateOf(false)

    // Кнопка на плашке
    private var buttonClickCount by mutableIntStateOf(0)
    private var isButtonHovered by mutableStateOf(false)
    private var wasPinching = false

    // IPD (до 100 мм)
    private var currentIpd by mutableFloatStateOf(63.0f)
    private var showIpdCrosshairUntil by mutableLongStateOf(0L)

    private var handLandmarks: List<List<FloatArray>> = emptyList()
    private var landmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        currentIpd = VrSettings.getIpdMm(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        initMediaPipe()
        startCamera()

        setContent {
            val pxPerMm = resources.displayMetrics.xdpi / 25.4f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070709)) // Тёмная монохромная VR-комната
                    .clickable { recenterPanel() }
            ) {
                VrStereoScreen(
                    ipdMm = currentIpd,
                    pxPerMm = pxPerMm,
                    showCrosshair = SystemClock.uptimeMillis() < showIpdCrosshairUntil,
                    rotationMatrix = rotationMatrix,
                    panelPos = Triple(panelWorldX, panelWorldY, panelWorldZ),
                    cubePos = Triple(cubeWorldX, cubeWorldY, cubeWorldZ),
                    hands = handLandmarks,
                    clickCount = buttonClickCount,
                    isButtonHovered = isButtonHovered,
                    isCubeHovered = isCubeHovered,
                    isCubeGrabbed = isCubeGrabbed
                )
            }
        }
    }

    private fun changeIpd(delta: Float) {
        val updated = (currentIpd + delta).coerceIn(50f, 100f)
        currentIpd = updated
        VrSettings.setIpdMm(this, updated)
        showIpdCrosshairUntil = SystemClock.uptimeMillis() + 1800L
    }

    // Регулировка IPD кнопками громкости без GUI на экране
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    changeIpd(0.5f)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    changeIpd(-0.5f)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun initMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(VrSettings.getConfidence(this))
            .setMinTrackingConfidence(VrSettings.getConfidence(this))
            .setNumHands(2)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                processHands(result)
            }
            .build()

        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val selector = if (VrSettings.isFrontCamera(this)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeImage(imageProxy)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val rawBitmap = imageProxy.toBitmap()
        val degrees = imageProxy.imageInfo.rotationDegrees

        val rotatedBitmap = if (degrees != 0) {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        landmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    // Векторы направления камеры телефона в Landscape (с исправленными знаками)
    private fun getCameraAxes(): Triple<FloatArray, FloatArray, FloatArray> {
        val f = floatArrayOf(-rotationMatrix.get(2), -rotationMatrix.get(6), -rotationMatrix.get(10))
        val u = floatArrayOf(rotationMatrix.get(0), rotationMatrix.get(4), rotationMatrix.get(8))
        val r = floatArrayOf(-rotationMatrix.get(1), -rotationMatrix.get(5), -rotationMatrix.get(9))
        return Triple(f, u, r)
    }

    private fun recenterPanel() {
        val (f, _, _) = getCameraAxes()
        panelWorldX = f.get(0) * 1.6f
        panelWorldY = f.get(1) * 1.6f
        panelWorldZ = f.get(2) * 1.6f
    }

    private fun processHands(result: HandLandmarkerResult) {
        val hands = mutableListOf<List<FloatArray>>()
        var pinchDetected = false
        var cursorOnButton = false
        var cursorOnCube = false

        val (f, u, r) = getCameraAxes()

        result.landmarks().forEach { landmarkList ->
            val points = landmarkList.map { floatArrayOf(it.x(), it.y(), it.z()) }
            hands.add(points)

            if (points.size >= 9) {
                val p4 = points.get(4) // большой
                val p8 = points.get(8) // указательный (курсор)

                val dx = p4.get(0) - p8.get(0)
                val dy = p4.get(1) - p8.get(1)
                val dz = p4.get(2) - p8.get(2)
                val dist = sqrt(dx * dx + dy * dy + dz * dz)

                if (dist < 0.055f) {
                    pinchDetected = true
                }

                val curX = p8.get(0)
                val curY = p8.get(1)

                // 1. Проверка наведения на кнопку плашки
                val relX = panelWorldX * r.get(0) + panelWorldY * r.get(1) + panelWorldZ * r.get(2)
                val relY = panelWorldX * u.get(0) + panelWorldY * u.get(1) + panelWorldZ * u.get(2)
                val relZ = panelWorldX * f.get(0) + panelWorldY * f.get(1) + panelWorldZ * f.get(2)

                if (relZ > 0.3f) {
                    // ИСПРАВЛЕНИЕ НАПРАВЛЕНИЯ: минус для устранения ухода в сторону
                    val pNormX = 0.5f - (relX / relZ) * 0.65f
                    val pNormY = 0.5f + (relY / relZ) * 0.65f

                    if (abs(curX - pNormX) < 0.12f && abs(curY - (pNormY + 0.05f)) < 0.07f) {
                        cursorOnButton = true
                    }
                }

                // 2. Проверка наведения на 3D-Куб
                val cRelX = cubeWorldX * r.get(0) + cubeWorldY * r.get(1) + cubeWorldZ * r.get(2)
                val cRelY = cubeWorldX * u.get(0) + cubeWorldY * u.get(1) + cubeWorldZ * u.get(2)
                val cRelZ = cubeWorldX * f.get(0) + cubeWorldY * f.get(1) + cubeWorldZ * f.get(2)

                if (cRelZ > 0.3f) {
                    val cNormX = 0.5f - (cRelX / cRelZ) * 0.65f
                    val cNormY = 0.5f + (cRelY / cRelZ) * 0.65f

                    if (abs(curX - cNormX) < 0.10f && abs(curY - cNormY) < 0.10f) {
                        cursorOnCube = true
                    }
                }

                // Перемещение куба рукой при активном захвате щипком
                if (isCubeGrabbed && pinchDetected) {
                    val targetDist = 1.3f
                    val handOffX = (curX - 0.5f) * 1.5f
                    val handOffY = (0.5f - curY) * 1.5f

                    cubeWorldX = f.get(0) * targetDist - r.get(0) * handOffX + u.get(0) * handOffY
                    cubeWorldY = f.get(1) * targetDist - r.get(1) * handOffX + u.get(1) * handOffY
                    cubeWorldZ = f.get(2) * targetDist - r.get(2) * handOffX + u.get(2) * handOffY
                }
            }
        }

        handLandmarks = hands
        isButtonHovered = cursorOnButton
        isCubeHovered = cursorOnCube

        if (pinchDetected && !wasPinching) {
            wasPinching = true
            runOnUiThread {
                if (isCubeHovered) {
                    // Захват куба
                    isCubeGrabbed = true
                    triggerVibration(60)
                } else if (isButtonHovered) {
                    // Клик по кнопке
                    buttonClickCount++
                    triggerVibration(40)
                } else {
                    // Щипок в воздухе центрирует плашку
                    recenterPanel()
                }
            }
        } else if (!pinchDetected) {
            wasPinching = false
            if (isCubeGrabbed) {
                isCubeGrabbed = false
                triggerVibration(20) // виброотклик при отпускании
            }
        }
    }

    private fun triggerVibration(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        landmarker?.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            if (!isCalibrated) {
                recenterPanel()
                isCalibrated = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun VrStereoScreen(
    ipdMm: Float,
    pxPerMm: Float,
    showCrosshair: Boolean,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    cubePos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>,
    clickCount: Int,
    isButtonHovered: Boolean,
    isCubeHovered: Boolean,
    isCubeGrabbed: Boolean
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = true, ipdMm, pxPerMm, showCrosshair, rotationMatrix, panelPos, cubePos, hands, clickCount, isButtonHovered, isCubeHovered, isCubeGrabbed)
        }

        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF151518)))

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = false, ipdMm, pxPerMm, showCrosshair, rotationMatrix, panelPos, cubePos, hands, clickCount, isButtonHovered, isCubeHovered, isCubeGrabbed)
        }
    }
}

@Composable
fun EyeViewport(
    isLeftEye: Boolean,
    ipdMm: Float,
    pxPerMm: Float,
    showCrosshair: Boolean,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    cubePos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>,
    clickCount: Int,
    isButtonHovered: Boolean,
    isCubeHovered: Boolean,
    isCubeGrabbed: Boolean
) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(10f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    val buttonTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    val panelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(235, 18, 18, 22)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    val borderPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
    }

    val buttonPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    val gridPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(40, 255, 255, 255) // Мягкие белые линии сетки комнаты
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
    }

    val cubePaint = remember {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val eyeWidth = size.width
        val eyeHeight = size.height

        val eyeSign = if (isLeftEye) 1f else -1f
        val ipdShiftPixels = eyeSign * ((ipdMm - 63f) * 0.5f * pxPerMm)

        val centerX = eyeWidth / 2f + ipdShiftPixels
        val centerY = eyeHeight / 2f

        val fov = 750f

        val fX = -rotationMatrix.get(2)
        val fY = -rotationMatrix.get(6)
        val fZ = -rotationMatrix.get(10)

        val uX = rotationMatrix.get(0)
        val uY = rotationMatrix.get(4)
        val uZ = rotationMatrix.get(8)

        val rX = -rotationMatrix.get(1)
        val rY = -rotationMatrix.get(5)
        val rZ = -rotationMatrix.get(9)

        // Функция 3D-проекции точки в координаты экрана (с исправленной инверсией)
        fun project3D(x: Float, y: Float, z: Float): Pair<Float, Float>? {
            val lX = x * rX + y * rY + z * rZ
            val lY = x * uX + y * uY + z * uZ
            val lZ = x * fX + y * fY + z * fZ

            if (lZ <= 0.25f) return null

            // ИСПРАВЛЕНИЕ: строго противоположное движение для фиксации в пространстве
            val sx = centerX - (lX / lZ) * fov
            val sy = centerY + (lY / lZ) * fov
            return Pair(sx, sy)
        }

        // 1. Отрисовка 3D-сетки пола комнаты (черно-белая атмосфера)
        val floorY = -0.85f
        for (zLine in 1..8) {
            val zDist = zLine * 0.7f
            val p1 = project3D(-2.5f, floorY, zDist)
            val p2 = project3D(2.5f, floorY, zDist)
            if (p1 != null && p2 != null) {
                drawLine(
                    color = Color(0x28FFFFFF),
                    start = Offset(p1.first, p1.second),
                    end = Offset(p2.first, p2.second),
                    strokeWidth = 2f
                )
            }
        }
        for (xLine in -3..3) {
            val xDist = xLine * 0.7f
            val pStart = project3D(xDist, floorY, 0.7f)
            val pEnd = project3D(xDist, floorY, 5.5f)
            if (pStart != null && pEnd != null) {
                drawLine(
                    color = Color(0x28FFFFFF),
                    start = Offset(pStart.first, pStart.second),
                    end = Offset(pEnd.first, pEnd.second),
                    strokeWidth = 2f
                )
            }
        }

        // 2. Интерактивный 3D-Куб (можно брать и двигать щипком)
        val cX = cubePos.first
        val cY = cubePos.second
        val cZ = cubePos.third
        val hs = 0.12f // полуразмер куба (24 см)

        val v = arrayOf(
            project3D(cX - hs, cY - hs, cZ - hs),
            project3D(cX + hs, cY - hs, cZ - hs),
            project3D(cX + hs, cY + hs, cZ - hs),
            project3D(cX - hs, cY + hs, cZ - hs),
            project3D(cX - hs, cY - hs, cZ + hs),
            project3D(cX + hs, cY - hs, cZ + hs),
            project3D(cX + hs, cY + hs, cZ + hs),
            project3D(cX - hs, cY + hs, cZ + hs)
        )

        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )

        val cubeColor = when {
            isCubeGrabbed -> Color(0xFF00E676) // Ярко-зеленый при перемещении
            isCubeHovered -> Color(0xFF64B5F6) // Голубой при наведении
            else -> Color(0xE0FFFFFF)          // Чисто белый в покое
        }

        edges.forEach { (a, b) ->
            val pA = v.get(a)
            val pB = v.get(b)
            if (pA != null && pB != null) {
                drawLine(
                    color = cubeColor,
                    start = Offset(pA.first, pA.second),
                    end = Offset(pB.first, pB.second),
                    strokeWidth = if (isCubeGrabbed) 6f else 4f
                )
            }
        }

        // 3. Плашка «Привет мир» с кнопкой
        val panelProj = project3D(panelPos.first, panelPos.second, panelPos.third)
        if (panelProj != null) {
            val screenX = panelProj.first
            val screenY = panelProj.second

            val w = 330f
            val h = 180f
            val rect = RectF(screenX - w / 2, screenY - h / 2, screenX + w / 2, screenY + h / 2)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, panelPaint)
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, borderPaint)

                canvas.nativeCanvas.drawText("Привет мир", screenX, screenY - 24f, textPaint)

                val btnW = 270f
                val btnH = 62f
                val btnRect = RectF(screenX - btnW / 2, screenY + 14f, screenX + btnW / 2, screenY + 14f + btnH)

                buttonPaint.color = if (isButtonHovered) {
                    android.graphics.Color.argb(255, 60, 140, 255)
                } else {
                    android.graphics.Color.argb(160, 45, 45, 55)
                }

                canvas.nativeCanvas.drawRoundRect(btnRect, 16f, 16f, buttonPaint)

                val btnLabel = if (clickCount == 0) "Нажми меня (Щелчок)" else "Нажато: $clickCount!"
                canvas.nativeCanvas.drawText(btnLabel, screenX, screenY + 54f, buttonTextPaint)
            }
        }

        // 4. Белый скелет рук + курсор на указательном пальце
        val handConnections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20,
            5 to 9, 9 to 13, 13 to 17
        )

        hands.forEach { points ->
            handConnections.forEach { (a, b) ->
                if (a < points.size && b < points.size) {
                    val p1 = points.get(a)
                    val p2 = points.get(b)
                    drawLine(
                        color = Color.White,
                        start = Offset(p1.get(0) * eyeWidth + ipdShiftPixels, p1.get(1) * eyeHeight),
                        end = Offset(p2.get(0) * eyeWidth + ipdShiftPixels, p2.get(1) * eyeHeight),
                        strokeWidth = 6f
                    )
                }
            }

            points.forEachIndexed { idx, pt ->
                val pX = pt.get(0) * eyeWidth + ipdShiftPixels
                val pY = pt.get(1) * eyeHeight

                if (idx == 8) {
                    val cursorCol = when {
                        isCubeGrabbed -> Color(0xFF00E676)
                        isCubeHovered -> Color(0xFF64B5F6)
                        isButtonHovered -> Color(0xFF2979FF)
                        else -> Color.White
                    }
                    drawCircle(
                        color = cursorCol,
                        radius = 16f,
                        center = Offset(pX, pY),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 5f,
                        center = Offset(pX, pY)
                    )
                } else {
                    drawCircle(
                        color = Color.White,
                        radius = 7f,
                        center = Offset(pX, pY)
                    )
                }
            }
        }

        // 5. Временный прицельный крестик при изменении IPD кнопками громкости
        if (showCrosshair) {
            drawLine(
                color = Color(0x88FFFFFF),
                start = Offset(centerX - 35f, centerY),
                end = Offset(centerX + 35f, centerY),
                strokeWidth = 2.5f
            )
            drawLine(
                color = Color(0x88FFFFFF),
                start = Offset(centerX, centerY - 35f),
                end = Offset(centerX, centerY + 35f),
                strokeWidth = 2.5f
            )
            drawCircle(
                color = Color(0x44FFFFFF),
                radius = 26f,
                center = Offset(centerX, centerY)
            )
            textPaint.textSize = 28f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("IPD: ${"%.1f".format(ipdMm)} мм", centerX, centerY - 45f, textPaint)
            }
            textPaint.textSize = 42f
        }
    }
}
