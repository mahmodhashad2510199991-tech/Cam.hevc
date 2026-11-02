package com.example.cam2vault.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.camera.CameraCaps
import com.example.cam2vault.data.AppSettings
import com.example.cam2vault.data.Recorder
import com.example.cam2vault.data.VideoConfig
import com.example.cam2vault.overlay.FloatingPreviewManager
import com.example.cam2vault.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the camera, the recorder and the floating preview.
 * Can be started from the app, the notification, the floating button or the volume keys.
 */
class VideoRecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VideoRecordingService = this@VideoRecordingService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mutex = Mutex()

    private lateinit var cam: Camera2Manager
    private var overlay: FloatingPreviewManager? = null
    private var appSurface: Surface? = null
    private var overlaySurface: Surface? = null

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var recordingActive = false
    private var starting = false
    private var startJob: Job? = null
    private var timerJob: Job? = null
    private var startElapsed = 0L
    private var activeCameraId = ""
    private var activeConfig: VideoConfig? = null
    private var activityBound = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cam = Camera2Manager(this)
        cam.onCameraLost = { scope.launch { onCameraLost() } }
    }

    override fun onBind(intent: Intent?): IBinder {
        activityBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        activityBound = false
        scope.launch {
            mutex.withLock {
                appSurface = null
                if (!recordingActive) {
                    refreshIdleSession()
                }
            }
            if (!recordingActive && startJob?.isActive != true) {
                stopSelf()
            }
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START, ACTION_TOGGLE -> {
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (action == ACTION_START && (recordingActive || startJob?.isActive == true)) {
                    return START_NOT_STICKY
                }
                handleToggle()
            }
            ACTION_STOP -> scope.launch { mutex.withLock { stopRecordingInternal() } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        if (recordingActive) finishRecorder()
        overlay?.hide()
        cam.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ public API (used by the UI)

    /** Called by the app screen to show / hide the live preview inside the app. */
    fun attachAppPreview(surface: Surface?) {
        scope.launch {
            mutex.withLock {
                appSurface = surface
                if (!granted(Manifest.permission.CAMERA)) return@withLock
                if (recordingActive) reconfigureLive() else refreshIdleSession()
            }
        }
    }

    fun onSettingsChanged() {
        scope.launch {
            mutex.withLock {
                if (!recordingActive) refreshIdleSession()
            }
        }
    }

    /** Start if idle, stop if recording, cancel if counting down. */
    fun handleToggle() {
        when {
            recordingActive -> scope.launch { mutex.withLock { stopRecordingInternal() } }
            startJob?.isActive == true && !starting -> {
                startJob?.cancel()
                setState { copy(countdown = 0) }
                exitIfIdle()
            }
            startJob?.isActive == true -> { /* already starting */ }
            else -> startJob = scope.launch { runStartSequence() }
        }
    }

    // ------------------------------------------------------------------ start / stop

    private suspend fun runStartSequence() {
        var left = AppSettings.countdown(this)
        while (left > 0) {
            setState { copy(countdown = left, message = null) }
            delay(1000)
            left--
        }
        setState { copy(countdown = 0) }
        mutex.withLock {
            starting = true
            try {
                startRecordingInternal()
            } finally {
                starting = false
            }
        }
    }

    private fun currentCameraId(): String {
        val saved = AppSettings.cameraId(this)
        return if (saved != null && CameraCaps.listCameras(this).any { it.id == saved }) saved
        else CameraCaps.getBackCameraId(this)
    }

    private suspend fun startRecordingInternal() {
        if (!granted(Manifest.permission.CAMERA)) {
            fail("إذن الكاميرا غير ممنوح")
            return
        }
        val cameraId = currentCameraId()
        val configs = try {
            CameraCaps.queryConfigs(this, cameraId, true, AppSettings.bitratePct(this))
        } catch (e: Exception) {
            emptyList()
        }
        if (configs.isEmpty()) {
            fail("لا توجد جودات فيديو مدعومة")
            return
        }
        val wanted = AppSettings.configId(this)
        val primary = configs.firstOrNull { it.id == wanted }
            ?: configs.firstOrNull { it.id == "1920x1080_30" }
            ?: configs.first()

        val attempts = linkedSetOf(primary)
        configs.firstOrNull { it.resolution == primary.resolution && it.fps == 30 && !it.isHighSpeed }?.let { attempts.add(it) }
        configs.firstOrNull { it.id == "1920x1080_30" }?.let { attempts.add(it) }
        configs.firstOrNull { it.id == "1280x720_30" }?.let { attempts.add(it) }

        prepareOverlay(cameraId)

        var started: VideoConfig? = null
        for (cfg in attempts) {
            if (tryStart(cameraId, cfg)) {
                started = cfg
                break
            }
        }
        if (started == null) {
            detachOverlay()
            fail("تعذر بدء التسجيل")
            return
        }
        if (started.id != primary.id) {
            setMessage("الجهاز لم يدعم ${primary.shortName}، تم التحويل إلى ${started.shortName}")
        }
    }

    private suspend fun tryStart(cameraId: String, cfg: VideoConfig): Boolean {
        var newUri: Uri? = null
        return try {
            val (uri, fd) = createMediaStoreVideoFile(cfg)
            newUri = uri
            val mr = buildRecorder(cfg, cameraId, fd)
            recorder = mr
            pfd = fd
            videoUri = uri
            acquireWakeLock()

            val surfaces = listOf(mr.surface) + previewSurfaces(cfg.isHighSpeed)
            val ok = cam.configure(
                cameraId, surfaces, CameraDevice.TEMPLATE_RECORD, cfg.fps, cfg.isHighSpeed,
                AppSettings.stabilization(this)
            )
            if (!ok) throw IllegalStateException("camera configure failed")

            mr.start()
            startElapsed = SystemClock.elapsedRealtime()
            recordingActive = true
            activeCameraId = cameraId
            activeConfig = cfg
            setState {
                RecordingState0(
                    isRecording = true, elapsedMs = 0L, fileSizeBytes = 0L,
                    activeConfig = cfg, fileName = uri.lastPathSegment, message = message, countdown = 0
                )
            }
            overlay?.updateStatus(true, "00:00")
            startTimer(cfg)
            Log.i(TAG, "Recording started: ${cfg.shortName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "tryStart failed for ${cfg.shortName}: ${e.message}", e)
            discardFailedAttempt(newUri)
            false
        }
    }

    private fun buildRecorder(cfg: VideoConfig, cameraId: String, fd: ParcelFileDescriptor): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val withAudio = AppSettings.audio(this) && !cfg.isHighSpeed && granted(Manifest.permission.RECORD_AUDIO)
        if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        if (withAudio) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(192000)
            mr.setAudioSamplingRate(48000)
        }
        mr.setVideoSize(cfg.resolution.width, cfg.resolution.height)
        mr.setVideoFrameRate(cfg.fps)
        mr.setVideoEncodingBitRate(cfg.bitrate)
        mr.setOrientationHint(CameraCaps.sensorOrientation(this, cameraId))
        mr.setOutputFile(fd.fileDescriptor)
        mr.prepare()
        return mr
    }

    private fun discardFailedAttempt(uri: Uri?) {
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        if (uri != null) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
        }
        videoUri = null
    }

    /** Stops the recorder, closes the file and finalizes the MediaStore entry. */
    private fun finishRecorder() {
        recordingActive = false
        timerJob?.cancel()
        cam.stopRepeating()
        var valid = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaRecorder.stop failed (recording too short?)", e)
            valid = false
        }
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        val uri = videoUri
        videoUri = null
        if (uri != null) {
            try {
                if (valid) {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.DURATION, SystemClock.elapsedRealtime() - startElapsed)
                    }
                    contentResolver.update(uri, v, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Finalizing MediaStore entry failed", e)
            }
        }
        activeConfig = null
    }

    private suspend fun stopRecordingInternal() {
        if (!recordingActive) {
            exitIfIdle()
            return
        }
        val name = Recorder.state.value.fileName
        finishRecorder()
        setState { RecordingState0(message = if (name != null) "تم حفظ الفيديو: $name" else null) }
        scheduleMessageClear()

        overlaySurface = null
        refreshIdleSession()
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        exitIfIdle()
    }

    private suspend fun onCameraLost() {
        if (recordingActive) {
            setMessage("تم فقد الكاميرا (تطبيق آخر استخدمها). تم حفظ التسجيل.")
            mutex.withLock { stopRecordingInternal() }
        }
    }

    private fun exitIfIdle() {
        if (recordingActive || startJob?.isActive == true) return
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        if (!activityBound) stopSelf()
    }

    private fun fail(msg: String) {
        setMessage(msg)
        exitIfIdle()
    }

    // ------------------------------------------------------------------ sessions / preview

    private fun previewSurfaces(highSpeed: Boolean): List<Surface> {
        if (highSpeed) return emptyList()
        return listOfNotNull(appSurface, overlaySurface).filter { it.isValid }
    }

    private suspend fun refreshIdleSession() {
        if (recordingActive) return
        if (!granted(Manifest.permission.CAMERA)) return
        val surfaces = previewSurfaces(false)
        if (surfaces.isEmpty()) {
            cam.closeCamera()
            return
        }
        cam.configure(currentCameraId(), surfaces, CameraDevice.TEMPLATE_PREVIEW, 30, false, false)
    }

    private suspend fun reconfigureLive() {
        val cfg = activeConfig
        val rs = recorder?.surface
        if (recordingActive && cfg != null && rs != null) {
            cam.configure(
                activeCameraId, listOf(rs) + previewSurfaces(cfg.isHighSpeed),
                CameraDevice.TEMPLATE_RECORD, cfg.fps, cfg.isHighSpeed, AppSettings.stabilization(this)
            )
        } else {
            refreshIdleSession()
        }
    }

    // ------------------------------------------------------------------ floating preview

    private suspend fun prepareOverlay(cameraId: String) {
        if (!AppSettings.overlayEnabled(this)) return
        val mgr = overlay ?: FloatingPreviewManager(this).also { m ->
            overlay = m
            m.onSurfaceReady = { s ->
                overlaySurface = s
                if (!starting) {
                    scope.launch { mutex.withLock { reconfigureLive() } }
                }
            }
            m.onSurfaceDestroyed = { overlaySurface = null }
            m.onToggleRecord = { handleToggle() }
            m.onClose = { scope.launch { mutex.withLock { detachOverlay() } } }
        }
        if (!mgr.canDrawOverlays()) return
        if (!mgr.isShowing) mgr.show(CameraCaps.previewSize(this, cameraId))
        withTimeoutOrNull(1000) {
            while (overlaySurface == null) delay(25)
        }
    }

    private suspend fun detachOverlay() {
        overlaySurface = null
        reconfigureLive()
        overlay?.hide()
        overlay = null
    }

    // ------------------------------------------------------------------ timer / notification

    private fun startTimer(cfg: VideoConfig) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val e = SystemClock.elapsedRealtime() - startElapsed
                val size = try { pfd?.statSize ?: 0L } catch (_: Exception) { 0L }
                setState { copy(elapsedMs = e, fileSizeBytes = if (size > 0) size else fileSizeBytes) }
                overlay?.updateStatus(true, formatTime(e))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(cfg, e))

                val free = try {
                    StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                } catch (_: Exception) { Long.MAX_VALUE }
                if (free < 150L * 1024 * 1024) {
                    setMessage("المساحة شبه ممتلئة، تم إيقاف التسجيل وحفظه")
                    mutex.withLock { stopRecordingInternal() }
                    break
                }
            }
        }
    }

    private fun enterForeground(): Boolean {
        return try {
            val n = buildNotification(activeConfig, 0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = 0
                if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (types == 0) startForeground(NOTIFICATION_ID, n) else startForeground(NOTIFICATION_ID, n, types)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground", e)
            Toast.makeText(applicationContext, "تعذر بدء التسجيل من الخلفية. فعّل إذن \"الظهور فوق التطبيقات\" وافتح التطبيق مرة.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "التسجيل في الخلفية", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(cfg: VideoConfig?, elapsedMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VideoRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (cfg != null) "جاري التسجيل • ${formatTime(elapsedMs)}" else "Cam2Vault"
        val text = if (cfg != null) "${cfg.shortName} fps • HEVC" else "جاهز"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .build()
    }

    // ------------------------------------------------------------------ helpers

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setState(block: com.example.cam2vault.data.RecordingState.() -> com.example.cam2vault.data.RecordingState) {
        Recorder.state.value = Recorder.state.value.block()
    }

    private fun setMessage(msg: String) {
        setState { copy(message = msg) }
        scheduleMessageClear()
    }

    private fun scheduleMessageClear() {
        val current = Recorder.state.value.message
        scope.launch {
            delay(5000)
            if (Recorder.state.value.message == current) setState { copy(message = null) }
        }
    }

    @Suppress("FunctionName")
    private fun RecordingState0(
        isRecording: Boolean = false,
        countdown: Int = 0,
        elapsedMs: Long = 0L,
        fileSizeBytes: Long = 0L,
        activeConfig: VideoConfig? = null,
        fileName: String? = null,
        message: String? = null
    ) = com.example.cam2vault.data.RecordingState(isRecording, countdown, elapsedMs, fileSizeBytes, activeConfig, fileName, message)

    private fun createMediaStoreVideoFile(cfg: VideoConfig): Pair<Uri, ParcelFileDescriptor> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "VID_${ts}_${cfg.resolution.width}x${cfg.resolution.height}_${cfg.fps}fps_HEVC.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cam2Vault")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to insert MediaStore record")
        val fd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Failed to open file descriptor")
        return Pair(uri, fd)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cam2Vault:Recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val TAG = "VideoRecordingService"
        const val CHANNEL_ID = "cam2vault_recording_channel"
        const val NOTIFICATION_ID = 4001
        const val ACTION_START = "com.example.cam2vault.action.START"
        const val ACTION_STOP = "com.example.cam2vault.action.STOP"
        const val ACTION_TOGGLE = "com.example.cam2vault.action.TOGGLE"

        fun formatTime(ms: Long): String {
            val s = (ms / 1000) % 60
            val m = (ms / 60000) % 60
            val h = ms / 3600000
            return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
