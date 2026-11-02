package com.example.cam2vault.data

import android.content.Context

object AppSettings {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("cam2vault_settings", Context.MODE_PRIVATE)

    fun configId(c: Context): String? = sp(c).getString("config_id", null)
    fun setConfigId(c: Context, v: String) { sp(c).edit().putString("config_id", v).apply() }

    fun cameraId(c: Context): String? = sp(c).getString("camera_id", null)
    fun setCameraId(c: Context, v: String) { sp(c).edit().putString("camera_id", v).apply() }

    fun audio(c: Context) = sp(c).getBoolean("audio", true)
    fun setAudio(c: Context, v: Boolean) { sp(c).edit().putBoolean("audio", v).apply() }

    fun stabilization(c: Context) = sp(c).getBoolean("stabilization", true)
    fun setStabilization(c: Context, v: Boolean) { sp(c).edit().putBoolean("stabilization", v).apply() }

    fun showExperimental(c: Context) = sp(c).getBoolean("show_experimental", true)
    fun setShowExperimental(c: Context, v: Boolean) { sp(c).edit().putBoolean("show_experimental", v).apply() }

    fun bitratePct(c: Context) = sp(c).getInt("bitrate_pct", 100)
    fun setBitratePct(c: Context, v: Int) { sp(c).edit().putInt("bitrate_pct", v).apply() }

    fun countdown(c: Context) = sp(c).getInt("countdown", 0)
    fun setCountdown(c: Context, v: Int) { sp(c).edit().putInt("countdown", v).apply() }

    /** "off", "double_down" or "double_up" */
    fun volumeTrigger(c: Context) = sp(c).getString("volume_trigger", "double_down") ?: "double_down"
    fun setVolumeTrigger(c: Context, v: String) { sp(c).edit().putString("volume_trigger", v).apply() }

    fun overlayEnabled(c: Context) = sp(c).getBoolean("overlay_enabled", true)
    fun setOverlayEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("overlay_enabled", v).apply() }

    fun overlayWidthDp(c: Context) = sp(c).getInt("overlay_width_dp", 110)
    fun setOverlayWidthDp(c: Context, v: Int) { sp(c).edit().putInt("overlay_width_dp", v).apply() }

    fun overlayX(c: Context, def: Int) = sp(c).getInt("overlay_x", def)
    fun overlayY(c: Context, def: Int) = sp(c).getInt("overlay_y", def)
    fun setOverlayPos(c: Context, x: Int, y: Int) { sp(c).edit().putInt("overlay_x", x).putInt("overlay_y", y).apply() }
}
