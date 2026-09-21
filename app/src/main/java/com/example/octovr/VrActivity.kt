package com.example.octovr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    private var panelWorldX = 0f
    private var panelWorldY = 0f
    private var panelWorldZ = 1.5f

    private var handLandmarks: List<List<FloatArray>> = emptyList()

    private var landmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        initMediaPipe()
        startCamera()

        setContent {
            VrStereoScreen(
                ipdOffsetPx = VrSettings.getIpdMm(this) * 2.5f,
                rotationMatrix = rotationMatrix,
                panelPos = Triple(panelWorldX, panelWorldY, panelWorldZ),
                hands = handLandmarks
            )
        }
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeImage(imageProxy)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    private fun processHands(result: HandLandmarkerResult) {
        val hands = mutableListOf<List<FloatArray>>()
        result.landmarks().forEach { landmarkList ->
            val points = landmarkList.map { floatArrayOf(it.x(), it.y(), it.z()) }
            hands.add(points)

            // Проверка щипка: кончик большого (4) и кончик указательного (8)
            if (points.size >= 9) {
                val dx = points[4][0] - points[8][0]
                val dy = points[4] - points[8]
                val dz = points[4][2] - points[8][2]
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < 0.05f) {
                    runOnUiThread { recenterPanelInFrontOfUser() }
                }
            }
        }
        handLandmarks = hands
    }

    private fun recenterPanelInFrontOfUser() {
        panelWorldX = -remappedMatrix[2] * 1.5f
        panelWorldY = -remappedMatrix[6] * 1.5f
        panelWorldZ = -remappedMatrix[10] * 1.5f
    }

    override fun onResume() {
        super.onResume()
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
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun VrStereoScreen(
    ipdOffsetPx: Float,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = true, ipdOffsetPx, rotationMatrix, panelPos, hands)
        }

        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.DarkGray))

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            EyeViewport(isLeftEye = false, ipdOffsetPx, rotationMatrix, panelPos, hands)
        }
    }
}

@Composable
fun EyeViewport(
    isLeftEye: Boolean,
    ipdOffsetPx: Float,
    rotationMatrix: FloatArray,
    panelPos: Triple<Float, Float, Float>,
    hands: List<List<FloatArray>>
) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    val panelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(190, 30, 30, 30)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    val borderPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(255, 90, 160, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f + (if (isLeftEye) ipdOffsetPx else -ipdOffsetPx)
        val centerY = size.height / 2f

        val px = panelPos.first
        val py = panelPos.second
        val pz = panelPos.third

        val viewX = rotationMatrix[0] * px + rotationMatrix[4] * py + rotationMatrix[8] * pz
        val viewY = rotationMatrix * px + rotationMatrix[5] * py + rotationMatrix[9] * pz
        val viewZ = rotationMatrix[2] * px + rotationMatrix[6] * py + rotationMatrix[10] * pz

        if (viewZ > 0.3f) {
            val fov = 650f
            val screenX = centerX + (viewX / viewZ) * fov
            val screenY = centerY - (viewY / viewZ) * fov
            val scale = (1.5f / viewZ).coerceIn(0.5f, 2.5f)

            val w = 320f * scale
            val h = 130f * scale
            val rect = RectF(screenX - w / 2, screenY - h / 2, screenX + w / 2, screenY + h / 2)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, panelPaint)
                canvas.nativeCanvas.drawRoundRect(rect, 24f, 24f, borderPaint)
                textPaint.textSize = 42f * scale
                canvas.nativeCanvas.drawText("Привет мир", screenX, screenY + 14f * scale, textPaint)
            }
        }

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
                    val p1 = points[a]
                    val p2 = points[b]
                    drawLine(
                        color = Color(0xFF64B5F6),
                        start = Offset(p1[0] * size.width, p1 * size.height),
                        end = Offset(p2[0] * size.width, p2 * size.height),
                        strokeWidth = 5f
                    )
                }
            }

            points.forEachIndexed { idx, pt ->
                drawCircle(
                    color = if (idx in listOf(4, 8)) Color(0xFFFF5252) else Color.White,
                    radius = if (idx in listOf(4, 8)) 9f else 6f,
                    center = Offset(pt[0] * size.width, pt * size.height)
                )
            }
        }
    }
}
