package com.example.cam2vault.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the camera device and its capture session.
 * [configure] (re)creates the session for a given set of output surfaces.
 */
class Camera2Manager(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Cam2VaultCamera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null

    /** Called (on a background thread) when the camera was taken away or failed. */
    var onCameraLost: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    private suspend fun ensureOpen(id: String): CameraDevice {
        val cur = device
        if (cur != null && deviceId == id) return cur
        closeCamera()
        return suspendCancellableCoroutine { cont ->
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) {
                        device = camera
                        deviceId = id
                        cont.resume(camera)
                    } else {
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) {
                        device = null; deviceId = null; session = null
                        onCameraLost?.invoke()
                    }
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) {
                        device = null; deviceId = null; session = null
                        onCameraLost?.invoke()
                    }
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                }
            }, handler)
        }
    }

    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>, highSpeed: Boolean): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(s) else s.close()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                }
            }
            try {
                if (highSpeed) {
                    dev.createConstrainedHighSpeedCaptureSession(surfaces, cb, handler)
                } else {
                    dev.createCaptureSession(surfaces, cb, handler)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /**
     * Opens [cameraId] if needed, creates a session for [surfaces] and starts the repeating request.
     * @return true on success
     */
    suspend fun configure(
        cameraId: String,
        surfaces: List<Surface>,
        template: Int,
        fps: Int,
        highSpeed: Boolean,
        stabilization: Boolean
    ): Boolean {
        return try {
            val dev = ensureOpen(cameraId)
            session?.let { try { it.close() } catch (_: Exception) { } }
            session = null

            val sess = createSession(dev, surfaces, highSpeed)
            session = sess

            val ch = manager.getCameraCharacteristics(cameraId)
            val builder = dev.createCaptureRequest(template)
            surfaces.forEach { builder.addTarget(it) }

            val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            if (template == CameraDevice.TEMPLATE_RECORD) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chooseAeRange(ch, fps, highSpeed))
                if (stabilization && !highSpeed) {
                    val eis = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    if (eis?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    } else if (ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }
                }
            }

            if (highSpeed) {
                val hs = sess as CameraConstrainedHighSpeedCaptureSession
                hs.setRepeatingBurst(hs.createHighSpeedRequestList(builder.build()), null, handler)
            } else {
                sess.setRepeatingRequest(builder.build(), null, handler)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "configure failed (fps=$fps, highSpeed=$highSpeed)", e)
            false
        }
    }

    private fun chooseAeRange(ch: CameraCharacteristics, fps: Int, highSpeed: Boolean): Range<Int> {
        if (highSpeed) return Range(fps, fps)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.firstOrNull { it.lower <= fps && it.upper >= fps }
            ?: Range(fps, fps)
    }

    fun stopRepeating() {
        try {
            session?.stopRepeating()
            session?.abortCaptures()
        } catch (_: Exception) { }
    }

    fun closeCamera() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        val d = device
        device = null
        deviceId = null
        try { d?.close() } catch (_: Exception) { }
    }

    fun release() {
        closeCamera()
        thread.quitSafely()
    }

    companion object {
        const val TAG = "Camera2Manager"
    }
}
