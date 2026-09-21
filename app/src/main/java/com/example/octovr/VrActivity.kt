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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Матрица вращения головы (4x4)
    private val rotationMatrix = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

    // Мировое положение плашки в 3D (X, Y, Z)
    private var panelWorldX by mutableFloatStateOf(0f)
    private var panelWorldY by mutableFloatStateOf(0f)
    private var panelWorldZ by mutableFloatStateOf(1.5f)
    private var isCalibrated = false

    // Счётчик нажатий кнопки на плашке
    private var buttonClickCount by mutableIntStateOf(0)
    private var isButtonHovered by mutableStateOf(false)
    private var wasPinching = false

    // Текущий IPD (до 100 мм)
    private var currentIpd by mutableFloatStateOf(63.0f)
    private var showIpdCrosshairUntil by mutableLongStateOf(0L)

    private var handLandmarks: List<List<FloatArray>> = emptyList()
    private var latestCameraFrame by mutableStateOf<Bitmap?>(null)

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
                    .background(Color.Black)
                    .clickable { recenterPanel() }
            ) {
                VrStereoScreen(
                    ipdMm = currentIpd,
                    pxPerMm = pxPerMm,
                    showCrosshair = SystemClock.uptimeMillis() < showIpdCrosshairUntil,
                    passthroughAlpha = VrSettings.getPassthroughAlpha(this@VrActivity),
                    cameraFrame = latestCameraFrame,
                    rotationMatrix = rotationMatrix,
                    panelPos = Triple(panelWorldX, panelWorldY, panelWorldZ),
                    hands = handLandmarks,
                    clickCount = buttonClickCount,
                    isButtonHovered = isButtonHovered
                )

                // Оверлей в VR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(24.dp))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }

                    Row(
                        modifier = Modifier
                            .background(Color(0xCC1A1A20), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { changeIpd(-1.0f) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White)
                        }

                        Text(
                            text = "IPD: ${"%.1f".format(currentIpd)} мм (до 100)",
                            color = Color(0xFF64B5F6),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = { changeIpd(1.0f) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        }

                        IconButton(
                            onClick = { recenterPanel() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Центр", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun changeIpd(delta: Float) {
        val updated = (currentIpd + delta).coerceIn(50f, 100f)
        currentIpd = updated
        VrSettings.setIpdMm(this, updated)
        showIpdCrosshairUntil = SystemClock.uptimeMillis() + 2500L
    }

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

        latestCameraFrame = rotatedBitmap

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        landmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    // Векторная математика осей телефона в ландшафтном режиме
    // Исключает gimbal lock и уход плашки вправо при наклоне вверх
    private fun getCameraAxes(): Triple<FloatArray, FloatArray, FloatArray> {
        // Forward: из задней камеры телефона (-Z в локальных координатах)
        val f = floatArrayOf(-rotationMatrix.get(2), -rotationMatrix.get(6), -rotationMatrix.get(10))
        // Up: вверх в альбомной ориентации (+X в локальных координатах)
        val u = floatArrayOf(rotationMatrix.get(0), rotationMatrix.get(4), rotationMatrix.get(8))
        // Right: вправо в альбомной ориентации (-Y в локальных координатах)
        val r = floatArrayOf(-rotationMatrix.get(1), -rotationMatrix.get(5), -rotationMatrix.get(9))
        return Triple(f, u, r)
    }

    private fun recenterPanel() {
        val (f, _, _) = getCameraAxes()
        panelWorldX = f.get(0) * 1.5f
        panelWorldY = f.get(1) * 1.5f
        panelWorldZ = f.get(2) * 1.5f
    }

    private fun processHands(result: HandLandmarkerResult) {
        val hands = mutableListOf<List<FloatArray>>()
        var pinchDetected = false
        var cursorOnButton = false

        val (f, u, r) = getCameraAxes()

        result.landmarks().forEach { landmarkList ->
            val points = landmarkList.map { floatArrayOf(it.x(), it.y(), it.z()) }
            hands.add(points)

            if (points.size >= 9) {
                val p4 = points.get(4) // кончик большого пальца
                val p8 = points.get(8) // кончик указательного пальца (кружок-курсор)

                val dx = p4.get(0) - p8.get(0)
                val dy = p4.get(1) - p8.get(1)
                val dz = p4.get(2) - p8.get(2)
                val dist = sqrt(dx * dx + dy * dy + dz * dz)

                if (dist < 0.055f) {
                    pinchDetected = true
                }

                // Проверяем, наведен ли кружок указательного пальца на кнопку
                // Курсор нормализован от 0 до 1
                val cursorNormX = p8.get(0)
                val cursorNormY = p8.get(1)

                // Проекция положения плашки в камеру
                val relX = panelWorldX * r.get(0) + panelWorldY * r.get(1) + panelWorldZ * r.get(2)
                val relY = panelWorldX * u.get(0) + panelWorldY * u.get(1) + panelWorldZ * u.get(2)
                val relZ = panelWorldX * f.get(0) + panelWorldY * f.get(1) + panelWorldZ * f.get(2)

                if (relZ > 0.3f) {
                    // Нормализованные координаты плашки в поле зрения
                    val panelNormX = 0.5f + (relX / relZ) * 0.6f
                    val panelNormY = 0.5f - (relY / relZ) * 0.6f

                    // Проверяем зону кнопки (нижняя половина плашки)
                    if (abs(cursorNormX - panelNormX) < 0.12f && abs(cursorNormY - (panelNormY + 0.04f)) < 0.07f) {
                        cursorOnButton = true
                    }
                }
            }
        }

        handLandmarks = hands
        isButtonHovered = cursorOnButton

        // Обработка клика щелчком
        if (pinchDetected && !wasPinching) {
            wasPinching = true
            runOnUiThread {
                if (isButtonHovered) {
                    // Клик по кнопке на плашке
                    buttonClickCount++
                    triggerVibration()
                } else {
                    // Щелчок в пустоте центрирует плашку перед собой
                    recenterPanel()
                }
            }
        } else if (!pinchDetected) {
            wasPinching = false
        }
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator?.vibrate(40)
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
    passthroughAlpha: Float,
    cameraFrame: Bitmap?,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>,
    clickCount: Int,
    isButtonHovered: Boolean
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = true, ipdMm, pxPerMm, showCrosshair, passthroughAlpha, cameraFrame, rotationMatrix, panelPos, hands, clickCount, isButtonHovered)
        }

        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF222222)))

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = false, ipdMm, pxPerMm, showCrosshair, passthroughAlpha, cameraFrame, rotationMatrix, panelPos, hands, clickCount, isButtonHovered)
        }
    }
}

@Composable
fun EyeViewport(
    isLeftEye: Boolean,
    ipdMm: Float,
    pxPerMm: Float,
    showCrosshair: Boolean,
    passthroughAlpha: Float,
    cameraFrame: Bitmap?,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>,
    clickCount: Int,
    isButtonHovered: Boolean
) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    val buttonTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    val panelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(235, 20, 20, 26)
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

    Canvas(modifier = Modifier.fillMaxSize()) {
        val eyeWidth = size.width
        val eyeHeight = size.height

        // 1. Сквозное видео камеры
        cameraFrame?.let { bmp ->
            if (passthroughAlpha > 0f) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(eyeWidth.toInt(), eyeHeight.toInt()),
                    alpha = passthroughAlpha
                )
            }
        }

        // Физическое смещение IPD (до 100 мм)
        val eyeSign = if (isLeftEye) 1f else -1f
        val ipdShiftPixels = eyeSign * ((ipdMm - 63f) * 0.5f * pxPerMm)

        val centerX = eyeWidth / 2f + ipdShiftPixels
        val centerY = eyeHeight / 2f

        // 2. Векторная проекция плашки
        // Forward, Up, Right в мировой системе
        val fX = -rotationMatrix.get(2)
        val fY = -rotationMatrix.get(6)
        val fZ = -rotationMatrix.get(10)

        val uX = rotationMatrix.get(0)
        val uY = rotationMatrix.get(4)
        val uZ = rotationMatrix.get(8)

        val rX = -rotationMatrix.get(1)
        val rY = -rotationMatrix.get(5)
        val rZ = -rotationMatrix.get(9)

        val dx = panelPos.first
        val dy = panelPos.second
        val dz = panelPos.third

        // Скалярные произведения с ортогональными осями
        val localX = dx * rX + dy * rY + dz * rZ
        val localY = dx * uX + dy * uY + dz * uZ
        val localZ = dx * fX + dy * fY + dz * fZ

        // Если плашка в поле зрения
        if (localZ > 0.3f) {
            val fov = 750f
            val screenX = centerX + (localX / localZ) * fov
            val screenY = centerY - (localY / localZ) * fov

            val w = 340f
            val h = 180f
            val rect = RectF(screenX - w / 2, screenY - h / 2, screenX + w / 2, screenY + h / 2)

            drawIntoCanvas { canvas ->
                // Фон плашки
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, panelPaint)
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, borderPaint)

                // Текст «Привет мир»
                canvas.nativeCanvas.drawText("Привет мир", screenX, screenY - 24f, textPaint)

                // Интерактивная кнопка на плашке
                val btnW = 280f
                val btnH = 64f
                val btnRect = RectF(screenX - btnW / 2, screenY + 12f, screenX + btnW / 2, screenY + 12f + btnH)

                // Подсветка кнопки при наведении курсора пальца
                if (isButtonHovered) {
                    buttonPaint.color = android.graphics.Color.argb(255, 41, 121, 255) // Ярко-синий Hover
                } else {
                    buttonPaint.color = android.graphics.Color.argb(180, 50, 50, 60)
                }

                canvas.nativeCanvas.drawRoundRect(btnRect, 16f, 16f, buttonPaint)

                val btnLabel = if (clickCount == 0) "Нажми меня (Щелчок)" else "Нажато: $clickCount раз!"
                canvas.nativeCanvas.drawText(btnLabel, screenX, screenY + 54f, buttonTextPaint)
            }
        }

        // 3. Белый скелет рук и курсор-кружок на кончике пальца
        val handConnections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20,
            5 to 9, 9 to 13, 13 to 17
        )

        hands.forEach { points ->
            // Линии костей
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

            // Суставы
            points.forEachIndexed { idx, pt ->
                val pX = pt.get(0) * eyeWidth + ipdShiftPixels
                val pY = pt.get(1) * eyeHeight

                if (idx == 8) {
                    // КРУЖОК-КУРСОР на кончике указательного пальца (как в Vision Pro)
                    drawCircle(
                        color = if (isButtonHovered) Color(0xFF2979FF) else Color.White,
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

        // 4. Прицельные крестики IPD
        if (showCrosshair) {
            drawLine(
                color = Color(0xAA64B5F6),
                start = Offset(centerX - 40f, centerY),
                end = Offset(centerX + 40f, centerY),
                strokeWidth = 3f
            )
            drawLine(
                color = Color(0xAA64B5F6),
                start = Offset(centerX, centerY - 40f),
                end = Offset(centerX, centerY + 40f),
                strokeWidth = 3f
            )
            drawCircle(
                color = Color(0x6664B5F6),
                radius = 30f,
                center = Offset(centerX, centerY)
            )
        }
    }
}
