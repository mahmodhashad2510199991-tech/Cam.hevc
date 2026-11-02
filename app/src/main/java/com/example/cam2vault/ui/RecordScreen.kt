package com.example.cam2vault.ui

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.cam2vault.camera.CameraCaps
import com.example.cam2vault.data.AppSettings
import com.example.cam2vault.data.Recorder
import com.example.cam2vault.service.VideoRecordingService

fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> String.format("%.1f MB", b / (1024.0 * 1024))
    else -> String.format("%d KB", b / 1024)
}

fun freeSpaceText(): String = try {
    formatBytes(StatFs(Environment.getExternalStorageDirectory().path).availableBytes)
} catch (e: Exception) {
    "—"
}

fun toggleRecordingFromUi(ctx: Context) {
    ContextCompat.startForegroundService(
        ctx, Intent(ctx, VideoRecordingService::class.java).setAction(VideoRecordingService.ACTION_TOGGLE)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(service: VideoRecordingService?, resumeTick: Int) {
    val ctx = LocalContext.current
    val state by Recorder.state.collectAsState()

    val cameras = remember(resumeTick) { try { CameraCaps.listCameras(ctx) } catch (e: Exception) { emptyList() } }
    var cameraId by remember(cameras) {
        mutableStateOf(
            AppSettings.cameraId(ctx)?.takeIf { id -> cameras.any { it.id == id } } ?: CameraCaps.getBackCameraId(ctx)
        )
    }
    val showExp = remember(resumeTick) { AppSettings.showExperimental(ctx) }
    val bitratePct = remember(resumeTick) { AppSettings.bitratePct(ctx) }
    val configs = remember(cameraId, showExp, bitratePct) {
        try { CameraCaps.queryConfigs(ctx, cameraId, showExp, bitratePct) } catch (e: Exception) { emptyList() }
    }
    var selectedId by remember(configs) {
        mutableStateOf(
            AppSettings.configId(ctx)?.takeIf { id -> configs.any { it.id == id } }
                ?: configs.firstOrNull { it.id == "1920x1080_30" }?.id
                ?: configs.firstOrNull()?.id
        )
    }
    var overlayOn by remember(resumeTick) { mutableStateOf(AppSettings.overlayEnabled(ctx)) }

    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    val svc by rememberUpdatedState(service)
    val camIdState by rememberUpdatedState(cameraId)

    LaunchedEffect(service, previewSurface) { service?.attachAppPreview(previewSurface) }
    LaunchedEffect(cameraId) { service?.onSettingsChanged() }

    val selected = configs.firstOrNull { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ---- status bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isRecording) {
                Text(
                    text = "● ${VideoRecordingService.formatTime(state.elapsedMs)}  •  ${formatBytes(state.fileSizeBytes)}",
                    color = Color(0xFFFF5252), fontSize = 15.sp
                )
                Text(text = state.activeConfig?.shortName ?: "", color = Color.White, fontSize = 13.sp)
            } else {
                Text(text = selected?.label ?: "—", color = Color.White, fontSize = 13.sp)
                Text(text = "المساحة: ${freeSpaceText()}", color = Color(0xFF9CA3AF), fontSize = 12.sp)
            }
        }

        // ---- preview
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            key(cameraId) {
                AndroidView(
                    modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f),
                    factory = { c ->
                        TextureView(c).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    val size = CameraCaps.previewSize(c, camIdState)
                                    st.setDefaultBufferSize(size.width, size.height)
                                    previewSurface = Surface(st)
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    svc?.attachAppPreview(null)
                                    previewSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    }
                )
            }
            if (state.countdown > 0) {
                Text(text = state.countdown.toString(), color = Color.White, fontSize = 96.sp)
            }
        }

        state.message?.let {
            Text(
                text = it, color = Color(0xFF38BDF8), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ---- lens selector
        if (cameras.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cameras) { cam ->
                    FilterChip(
                        selected = cam.id == cameraId,
                        enabled = !state.isRecording,
                        onClick = {
                            AppSettings.setCameraId(ctx, cam.id)
                            cameraId = cam.id
                        },
                        label = { Text(cam.label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // ---- mode selector (4K60 / 4K30 / 1080p ...)
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(configs) { cfg ->
                FilterChip(
                    selected = cfg.id == selectedId,
                    enabled = !state.isRecording,
                    onClick = {
                        AppSettings.setConfigId(ctx, cfg.id)
                        selectedId = cfg.id
                    },
                    label = { Text(cfg.shortName + if (cfg.experimental) " ⚠" else "", fontSize = 13.sp) }
                )
            }
        }
        if (selected?.experimental == true) {
            Text(
                text = "⚠ الجهاز لا يعلن دعم هذا الوضع. سنجرّبه، وإن فشل نرجع تلقائيًا إلى 30 إطارًا.",
                color = Color(0xFFFBBF24), fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }

        // ---- floating preview switch
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("معاينة عائمة فوق التطبيقات", color = Color.White, fontSize = 13.sp)
            Switch(
                checked = overlayOn,
                onCheckedChange = { on ->
                    overlayOn = on
                    AppSettings.setOverlayEnabled(ctx, on)
                    if (on && !Settings.canDrawOverlays(ctx)) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }

        // ---- record button
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .clickable { toggleRecordingFromUi(ctx) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (state.isRecording) 28.dp else 56.dp)
                        .clip(if (state.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(Color.Red)
                )
            }
        }
    }
}
