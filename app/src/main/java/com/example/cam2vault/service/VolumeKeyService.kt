package com.example.cam2vault.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.cam2vault.data.AppSettings

/**
 * Starts / stops recording by double-pressing a volume key,
 * even when another app is in front. Requires enabling the service in Accessibility settings.
 */
class VolumeKeyService : AccessibilityService() {

    private var lastDownTime = 0L
    private var swallowUp = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: return
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val mode = AppSettings.volumeTrigger(this)
        if (mode == "off") return false
        val target = if (mode == "double_up") KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        if (event.keyCode != target) return false

        if (event.action == KeyEvent.ACTION_UP) {
            if (swallowUp) {
                swallowUp = false
                return true
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime < DOUBLE_PRESS_MS) {
            lastDownTime = 0L
            swallowUp = true
            toggleRecording()
            return true
        }
        lastDownTime = now
        return false
    }

    private fun toggleRecording() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, VideoRecordingService::class.java).setAction(VideoRecordingService.ACTION_TOGGLE)
            )
        } catch (e: Exception) {
            Log.e("VolumeKeyService", "Cannot start recording service", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "تعذر التشغيل من الخلفية. فعّل \"الظهور فوق التطبيقات\".", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val DOUBLE_PRESS_MS = 600L
    }
}
