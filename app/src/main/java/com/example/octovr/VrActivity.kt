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
import android.view.Surface
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

    // Углы поворота головы (в градусах)
    private var headYaw by mutableFloatStateOf(0f)
    private var headPitch by mutableFloatStateOf(0f)
    private var initialYaw = 0f
    private var initialPitch = 0f
    private var isCalibrated = false

    // Положение плашки в комнате (азимут и возвышение)
    private var panelYaw by mutableFloatStateOf(0f)
    private var panelPitch by mutableFloatStateOf(0f)

    // Текущий IPD
    private var currentIpd by mutableFloatStateOf(63.0f)

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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { recenterPanel() } // Тап по экрану центрирует плашку перед вами
            ) {
                VrStereoScreen(
                    ipdMm = currentIpd,
                    passthroughAlpha = VrSettings.getPassthroughAlpha(this@VrActivity),
                    cameraFrame = latestCameraFrame,
                    headYaw = headYaw,
                    headPitch = headPitch,
                    panelYaw = panelYaw,
                    panelPitch = panelPitch,
                    hands = handLandmarks
                )

                // Верхняя панель: кнопка назад и настройка IPD прямо в VR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(24.dp))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }

                    // Быстрая регулировка IPD в VR
                    Row(
                        modifier = Modifier
                            .background(Color(0x99202025), RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { changeIpd(-0.5f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White)
                        }

                        Text(
                            text = "IPD: ${"%.1f".format(currentIpd)} мм (Громкость +/-)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        IconButton(
                            onClick = { changeIpd(0.5f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun changeIpd(delta: Float) {
        val updated = (currentIpd + delta).coerceIn(55f, 75f)
        currentIpd = updated
        VrSettings.setIpdMm(this, updated)
    }

    // Регулировка IPD кнопками громкости телефона прямо в гарнитуре
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                changeIpd(0.5f)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                changeIpd(-0.5f)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

        // В горизонтальном режиме корректируем вращение
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

            // Жест щипка: пальцы 4 и 8
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

    // Центрирует плашку ровно перед текущим взглядом пользователя
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

            // Переназначаем оси под альбомную ориентацию экрана
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )

            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            val rawYaw = Math.toDegrees(orientationAngles.get(0).toDouble()).toFloat()
            val rawPitch = Math.toDegrees(orientationAngles.get(1).toDouble()).toFloat()

            // Калибровка нуля при старте
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
            EyeViewport(isLeftEye = true, ipdMm, passthroughAlpha, cameraFrame, headYaw, headPitch, panelYaw, panelPitch, hands)
        }

        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF222222)))

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = false, ipdMm, passthroughAlpha, cameraFrame, headYaw, headPitch, panelYaw, panelPitch, hands)
        }
    }
}

@Composable
fun EyeViewport(
    isLeftEye: Boolean,
    ipdMm: Float,
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
            color = android.graphics.Color.argb(220, 20, 20, 24)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    val borderPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(255, 255, 255, 255) // Белая рамка
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val eyeWidth = size.width
        val eyeHeight = size.height

        // 1. Сквозное видео камеры (Passthrough)
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

        val centerX = eyeWidth / 2f
        val centerY = eyeHeight / 2f

        val ipdMeters = ipdMm / 1000f
        val eyeSign = if (isLeftEye) 1f else -1f

        // 2. Расчет положения плашки «Привет мир» в сферических координатах
        var relYaw = panelYaw - headYaw
        // Нормализация диапазона -180..180
        while (relYaw > 180f) relYaw -= 360f
        while (relYaw < -180f) relYaw += 360f

        val relPitch = panelPitch - headPitch

        // Если плашка в поле зрения (в пределах 70 градусов)
        if (abs(relYaw) < 70f && abs(relPitch) < 55f) {
            val fov = 750f
            val radYaw = Math.toRadians(relYaw.toDouble())
            val radPitch = Math.toRadians(relPitch.toDouble())

            // Стерео-глубина плашки на дистанции 1.5м
            val panelDistanceMeters = 1.5f
            val stereoShift = (eyeSign * (ipdMeters * 0.5f) / panelDistanceMeters) * fov

            val screenX = centerX + tan(radYaw).toFloat() * fov + stereoShift
            val screenY = centerY - tan(radPitch).toFloat() * fov

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

        val handParallax = eyeSign * (ipdMeters * 0.5f / 0.65f) * 400f

        hands.forEach { points ->
            // Белые линии костей
            handConnections.forEach { (a, b) ->
                if (a < points.size && b < points.size) {
                    val p1 = points.get(a)
                    val p2 = points.get(b)
                    drawLine(
                        color = Color.White,
                        start = Offset(p1.get(0) * eyeWidth + handParallax, p1.get(1) * eyeHeight),
                        end = Offset(p2.get(0) * eyeWidth + handParallax, p2.get(1) * eyeHeight),
                        strokeWidth = 6f
                    )
                }
            }

            // Белые точки суставов
            points.forEachIndexed { _, pt ->
                drawCircle(
                    color = Color.White,
                    radius = 7f,
                    center = Offset(pt.get(0) * eyeWidth + handParallax, pt.get(1) * eyeHeight)
                )
            }
        }
    }
}
