package com.example.cam2vault.camera

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import kotlin.math.abs

object CameraProUtils {

    /**
     * تصفية المقاسات لنسبة 16:9 الصارمة فقط (مع ترتيب تنازلي: 4K ثم 1080p ثم 720p)
     */
    fun filterStrict16by9Sizes(characteristics: CameraCharacteristics): List<Size> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

        return sizes.filter { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            abs(ratio - (16.0 / 9.0)) < 0.03
        }.sortedByDescending { it.width * it.height }
    }

    /**
     * فحص دعم معدل 60 إطار بالثانية (60 FPS)
     */
    fun getTarget60FpsRange(characteristics: CameraCharacteristics): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        return ranges?.firstOrNull { it.lower >= 60 && it.upper >= 60 }
            ?: ranges?.firstOrNull { it.upper >= 60 }
    }
}
