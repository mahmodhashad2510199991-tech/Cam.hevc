package com.example.cam2vault.ui

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.data.VideoConfig
import com.example.cam2vault.overlay.FloatingPreviewManager
import com.example.cam2vault.service.VideoRecordingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    camera2Manager: Camera2Manager,
    getService: () -> VideoRecordingService?
) {
    val context = LocalContext.current
    val floatingManager = remember { FloatingPreviewManager(context) }
    var isFloatingActive by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val cameraId = remember { camera2Manager.getBackCameraId() }
    val supportedConfigs = remember(cameraId) {
        camera2Manager.querySupportedVideoConfigurations(cameraId)
    }

    var selectedConfig by remember {
        mutableStateOf(supportedConfigs.firstOrNull { it.is4K && it.fps == 60 }
            ?: supportedConfigs.firstOrNull { it.fps == 60 }
            ?: supportedConfigs.firstOrNull()
            ?: VideoConfig(
                id = "default",
                resolution = android.util.Size(1920, 1080),
                fps = 30,
                bitrate = 14_000_000,
                label = "1080p FHD (1920x1080)"
            ))
    }

    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    val service = getService()
    val recordingState by (service?.recordingState?.collectAsState()
        ?: remember { mutableStateOf(VideoRecordingService.RecordingState()) })

    LaunchedEffect(cameraId) {
        try {
            camera2Manager.openCamera(cameraId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // معاينة الكاميرا داخل التطبيق
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            previewSurface = Surface(st)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            previewSurface?.release()
                            previewSurface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }
        )

        // شريط علوي احترافي (HUD)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // زر النافذة العائمة المباشر
                Button(
                    onClick = {
                        if (!floatingManager.canDrawOverlays()) {
                            context.startActivity(floatingManager.requestOverlayPermissionIntent())
                            Toast.makeText(context, "يرجى تفعيل إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                        } else {
                            if (!isFloatingActive) {
                                floatingManager.showFloatingPreview()
                                isFloatingActive = true
                            } else {
                                floatingManager.hideFloatingPreview()
                                isFloatingActive = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFloatingActive) Color(0xFF10B981) else Color.Black.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (isFloatingActive) "إغلاق المعاينة" else "نافذة عائمة 16:9", fontSize = 12.sp)
                }

                // زر خيارات السماح دائماً وزرار الصوت
                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.White)
                }
            }

            // إشعار استمرار التسجيل وقفل الشاشة
            if (recordingState.isRecording) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Red))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تسجيل HEVC نشط في الخلفية • يمكنك إطفاء الشاشة أو تصفح التطبيقات بأمان",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // شريط الأوضاع الاحترافي السفلي (4K 60FPS / 1080P / 16:9)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // شريط تبديل الجودات السريع (Horizontal Mode Chips)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                supportedConfigs.forEach { config ->
                    val isSelected = config.id == selectedConfig.id
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0xFFE53935) else Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color.White else Color.Gray.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clickable(enabled = !recordingState.isRecording) {
                                selectedConfig = config
                            }
                    ) {
                        Text(
                            text = "${if (config.is4K) "4K" else "${config.resolution.height}p"} • ${config.fps} FPS",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // عداد الوقت المباشر
            if (recordingState.isRecording) {
                val sec = (recordingState.elapsedMs / 1000) % 60
                val min = (recordingState.elapsedMs / (1000 * 60)) % 60
                val hrs = (recordingState.elapsedMs / (1000 * 60 * 60))
                val timeString = String.format("%02d:%02d:%02d", hrs, min, sec)

                Text(
                    text = "● $timeString",
                    color = Color.Red,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // زر التسجيل الدائري الرئيسي
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .clickable {
                        val activeService = getService() ?: return@clickable
                        if (recordingState.isRecording) {
                            activeService.stopRecording()
                        } else {
                            activeService.startRecording(
                                config = selectedConfig,
                                cameraManager = camera2Manager,
                                cameraId = cameraId,
                                previewSurface = previewSurface
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (recordingState.isRecording) 30.dp else 60.dp)
                        .clip(if (recordingState.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(Color.Red)
                )
            }
        }

        // نافذة إعدادات السماح دائماً وزرار الصوت
        if (showSettingsSheet) {
            AlertDialog(
                onDismissRequest = { showSettingsSheet = false },
                title = { Text("إعدادات التسجيل الاحترافي الخفي") },
                text = {
                    Column {
                        Text("لتشغيل ميزات الخلفية وزرار الصوت اضغط لتفعيل الأذونات:", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // خيار 1: استهلاك الطاقة والسماح دائماً
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text("⚡ السماح دائماً (إلغاء قيود البطارية)")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // خيار 2: خدمة زرار الصوت
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                Toast.makeText(context, "فعل Cam2Vault من قائمة خدمات إمكانية الوصول", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text("🔊 تفعيل زرار الصوت لبدء/إيقاف التسجيل")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsSheet = false }) {
                        Text("تم")
                    }
                }
            )
        }
    }
}
