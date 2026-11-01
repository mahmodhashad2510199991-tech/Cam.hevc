package com.example.cam2vault.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

class VolumeKeyService : AccessibilityService() {

    private var lastClickTime: Long = 0
    private val DOUBLE_CLICK_TIME_DELTA: Long = 400

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val clickTime = SystemClock.uptimeMillis()
            if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                toggleRecording()
                lastClickTime = 0
                return true
            }
            lastClickTime = clickTime
        }
        return super.onKeyEvent(event)
    }

    private fun toggleRecording() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        
        val intent = Intent("com.example.cam2vault.ACTION_TOGGLE_RECORD").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
