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

    private val rotationMatrix = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
    private val remappedMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)

    // Текущие углы поворота головы
    private var headYaw by mutableFloatStateOf(0f)
    private var headPitch by mutableFloatStateOf(0f)

    private var initialYaw = 0f
    private var initialPitch = 0f
    private var isCalibrated = false

    // Мировое положение плашки в градусах
    private var panelYaw by mutableFloatStateOf(0f)
    private var panelPitch by mutableFloatStateOf(0f)

    // Текущий IPD (мм)
    private var currentIpd by mutableFloatStateOf(63.0f)
    private var showIpdCrosshairUntil by mutableLongStateOf(0L)

    private var handLandmarks: List<List<FloatArray>> = emptyList()
    private var latestCameraFrame by mutableStateOf<Bitmap?>(null)

    private var landmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        currentIpd = VrSettings.getIpdMm(this)

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
                    headYaw = headYaw,
                    headPitch = headPitch,
                    panelYaw = panelYaw,
                    panelPitch = panelPitch,
                    hands = handLandmarks
                )

                // Оверлей управления прямо в VR
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

                    // Панель IPD и центровки
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
                            text = "IPD: ${"%.1f".format(currentIpd)} мм (Громкость +/-)",
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
        val updated = (currentIpd + delta).coerceIn(52f, 78f)
        currentIpd = updated
        VrSettings.setIpdMm(this, updated)
        showIpdCrosshairUntil = SystemClock.uptimeMillis() + 2500L
    }

    // Перехват кнопок громкости телефона
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

    private fun processHands(result: HandLandmarkerResult) {
        val hands = mutableListOf<List<FloatArray>>()
        result.landmarks().forEach { landmarkList ->
            val points = landmarkList.map { floatArrayOf(it.x(), it.y(), it.z()) }
            hands.add(points)

            // Щипок пальцев (большой 4 и указательный 8)
            if (points.size >= 9) {
                val p4 = points.get(4)
                val p8 = points.get(8)
                val dx = p4.get(0) - p8.get(0)
                val dy = p4.get(1) - p8.get(1)
                val dz = p4.get(2) - p8.get(2)
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < 0.05f) {
                    runOnUiThread { recenterPanel() }
                }
            }
        }
        handLandmarks = hands
    }

    // Центрирует плашку прямо перед глазами пользователя
    private fun recenterPanel() {
        panelYaw = headYaw
        panelPitch = headPitch
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

            // Альбомная переориентация осей
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )

            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            val rawYaw = Math.toDegrees(orientationAngles.get(0).toDouble()).toFloat()
            val rawPitch = Math.toDegrees(orientationAngles.get(1).toDouble()).toFloat()

            if (!isCalibrated) {
                initialYaw = rawYaw
                initialPitch = rawPitch
                panelYaw = 0f
                panelPitch = 0f
                isCalibrated = true
            }

            headYaw = rawYaw - initialYaw
            headPitch = rawPitch - initialPitch
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
    headYaw: Float,
    headPitch: Float,
    panelYaw: Float,
    panelPitch: Float,
    hands: List<List<FloatArray>>
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = true, ipdMm, pxPerMm, showCrosshair, passthroughAlpha, cameraFrame, headYaw, headPitch, panelYaw, panelPitch, hands)
        }

        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF222222)))

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = false, ipdMm, pxPerMm, showCrosshair, passthroughAlpha, cameraFrame, headYaw, headPitch, panelYaw, panelPitch, hands)
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
    headYaw: Float,
    headPitch: Float,
    panelYaw: Float,
    panelPitch: Float,
    hands: List<List<FloatArray>>
) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 46f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    val panelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(230, 20, 20, 24)
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

        // Физическое смещение IPD: сдвиг центров линз в пикселях
        // Базовый IPD = 63 мм. Разница смещает картинку для идеального совмещения линз очков
        val eyeSign = if (isLeftEye) 1f else -1f
        val ipdShiftPixels = eyeSign * ((ipdMm - 63f) * 0.5f * pxPerMm)

        val centerX = eyeWidth / 2f + ipdShiftPixels
        val centerY = eyeHeight / 2f

        // 2. Расчет положения плашки «Привет мир»
        var dYaw = panelYaw - headYaw
        while (dYaw > 180f) dYaw -= 360f
        while (dYaw < -180f) dYaw += 360f

        val dPitch = panelPitch - headPitch

        // Пикселей на 1 градус поворота
        val pxPerDeg = eyeWidth / 70f

        // ИСПРАВЛЕНИЕ: верные знаки направлений
        // При повороте головы влево (dYaw > 0) плашка смещается вправо
        // При наклоне головы вверх плашка смещается вниз экрана
        val screenX = centerX + dYaw * pxPerDeg
        val screenY = centerY + dPitch * pxPerDeg

        // Рисуем плашку, если она в поле зрения
        if (abs(dYaw) < 55f && abs(dPitch) < 45f) {
            val w = 310f
            val h = 120f
            val rect = RectF(screenX - w / 2, screenY - h / 2, screenX + w / 2, screenY + h / 2)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(rect, 20f, 20f, panelPaint)
                canvas.nativeCanvas.drawRoundRect(rect, 20f, 20f, borderPaint)
                canvas.nativeCanvas.drawText("Привет мир", screenX, screenY + 14f, textPaint)
            }
        }

        // 3. Скелет рук чисто БЕЛОГО цвета
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

            points.forEachIndexed { _, pt ->
                drawCircle(
                    color = Color.White,
                    radius = 7f,
                    center = Offset(pt.get(0) * eyeWidth + ipdShiftPixels, pt.get(1) * eyeHeight)
                )
            }
        }

        // 4. Прицельные крестики калибровки IPD (показываются при настройке громкостью)
        if (showCrosshair) {
            drawLine(
                color = Color(0xAA64B5F6),
                start = Offset(centerX - 35f, centerY),
                end = Offset(centerX + 35f, centerY),
                strokeWidth = 3f
            )
            drawLine(
                color = Color(0xAA64B5F6),
                start = Offset(centerX, centerY - 35f),
                end = Offset(centerX, centerY + 35f),
                strokeWidth = 3f
            )
            drawCircle(
                color = Color(0x6664B5F6),
                radius = 25f,
                center = Offset(centerX, centerY)
            )
        }
    }
}
