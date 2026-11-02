package com.example.cam2vault.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.cam2vault.service.VideoRecordingService
import com.example.cam2vault.ui.theme.Cam2VaultTheme

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<VideoRecordingService?>(null)
    private var resumeTick by mutableIntStateOf(0)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VideoRecordingService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Cam2VaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreenContent(service, resumeTick)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, VideoRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onStop() {
        service?.attachAppPreview(null)
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }
}

@Composable
fun MainScreenContent(service: VideoRecordingService?, resumeTick: Int) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(checkAllPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = checkAllPermissions(context)
        if (!hasPermissions) {
            Toast.makeText(context, "الكاميرا والمايك ضروريان للتسجيل", Toast.LENGTH_LONG).show()
        }
    }

    fun requestAll() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(required.toTypedArray())
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) requestAll()
    }
    LaunchedEffect(resumeTick) {
        hasPermissions = checkAllPermissions(context)
    }

    if (hasPermissions) {
        AppRoot(service, resumeTick)
    } else {
        PermissionRequestView(onRequestAgain = { requestAll() })
    }
}

@Composable
fun AppRoot(service: VideoRecordingService?, resumeTick: Int) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                    label = { Text("تسجيل") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("المكتبة") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("الإعدادات") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> RecordScreen(service, resumeTick)
                1 -> LibraryScreen(resumeTick)
                else -> SettingsScreen(service, resumeTick)
            }
        }
    }
}

fun checkAllPermissions(context: Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return camera && audio
}

@Composable
fun PermissionRequestView(onRequestAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "الأذونات مطلوبة", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "يحتاج Cam2Vault إلى إذن الكاميرا والمايك لتسجيل فيديو HEVC عالي الدقة في الخلفية.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestAgain) { Text("منح الأذونات") }
    }
}
