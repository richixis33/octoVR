package com.example.octovr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.octovr.ui.theme.OctoVRTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OctoVRTheme {
                MainAppScaffold()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == 0) "OctoVR" else "Настройки и О приложении",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
                    label = { Text("Главная") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = { Text("Настройки") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onEnterVr = {
                        if (!hasCameraPermission) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            context.startActivity(Intent(context, VrActivity::class.java))
                        }
                    }
                )
                1 -> SettingsAndAboutScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEnterVr: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("OctoVR Experience", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "3DoF трекинг головы, Side-by-Side (SBS) стерео-режим и распознавание рук MediaPipe с жестом щипка.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            onClick = onEnterVr
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Войти в VR", style = MaterialTheme.typography.titleMedium)
        }

        if (!hasCameraPermission) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Требуется доступ к камере", style = MaterialTheme.typography.titleSmall)
                        Text("Для отслеживания рук и сканирования QR", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onRequestPermission) {
                        Text("Разрешить")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsAndAboutScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var ipd by remember { mutableFloatStateOf(VrSettings.getIpdMm(context)) }
    var confidence by remember { mutableFloatStateOf(VrSettings.getConfidence(context)) }
    var smoothing by remember { mutableStateOf(VrSettings.isSmoothing(context)) }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val text = result.contents
            val regex = """(?:ipd[=:]\s*|ipd\s*)?([5-7][0-9](?:\.[0-9]+)?)""".toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            if (match != null) {
                val parsed = match.groupValues.get(1).toFloatOrNull()
                if (parsed != null && parsed in 50f..80f) {
                    ipd = parsed
                    VrSettings.setIpdMm(context, parsed)
                    Toast.makeText(context, "IPD установлен: ${parsed} мм", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Распознан QR: $text", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Данные QR: $text", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Калибровка IPD (Межзрачковое расстояние)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Подстройте расстояние между линзами ваших VR-очков: ${"%.1f".format(ipd)} мм", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                Slider(
                    value = ipd,
                    onValueChange = {
                        ipd = it
                        VrSettings.setIpdMm(context, it)
                    },
                    valueRange = 55f..75f
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val options = ScanOptions().apply {
                            setPrompt("Наведите камеру на QR-код профиля гарнитуры")
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                        }
                        qrLauncher.launch(options)
                    }
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Сканировать QR-код гарнитуры")
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Настройки MediaPipe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Text("Порог уверенности детекции: ${"%.2f".format(confidence)}")
                Slider(
                    value = confidence,
                    onValueChange = {
                        confidence = it
                        VrSettings.setConfidence(context, it)
                    },
                    valueRange = 0.3f..0.9f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Сглаживание движений рук")
                    Switch(
                        checked = smoothing,
                        onCheckedChange = {
                            smoothing = it
                            VrSettings.setSmoothing(context, it)
                        }
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("OctoVR") },
                    supportingContent = { Text("Версия 1.1.0 • Material You + MediaPipe SBS") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
            }
        }
    }
}
