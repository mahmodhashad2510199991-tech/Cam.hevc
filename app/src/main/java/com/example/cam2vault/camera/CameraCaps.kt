package com.example.cam2vault.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import com.example.cam2vault.data.CameraInfo
import com.example.cam2vault.data.VideoConfig
import kotlin.math.hypot

/** Read-only queries about the cameras of this device (no camera is opened here). */
object CameraCaps {
    private const val TAG = "CameraCaps"

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun listCameras(ctx: Context): List<CameraInfo> {
        val m = mgr(ctx)
        val out = mutableListOf<CameraInfo>()
        for (id in m.cameraIdList) {
            try {
                val ch = m.getCameraCharacteristics(id)
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                if (map.getOutputSizes(MediaRecorder::class.java).isNullOrEmpty()) continue
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> backLabel(ch)
                    else -> "External"
                }
                out.add(CameraInfo(id, "$label ($id)", facing))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id", e)
            }
        }
        return out
    }

    private fun backLabel(ch: CameraCharacteristics): String {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sz = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f == null || sz == null) return "Back"
        val diag = hypot(sz.width.toDouble(), sz.height.toDouble())
        val eq = f * 43.27 / diag
        return when {
            eq < 19 -> "Ultra-wide"
            eq < 40 -> "Main"
            else -> "Tele"
        }
    }

    fun getBackCameraId(ctx: Context): String {
        val cams = listCameras(ctx)
        val back = cams.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        return (back.firstOrNull { it.label.startsWith("Main") } ?: back.firstOrNull() ?: cams.firstOrNull())?.id
            ?: mgr(ctx).cameraIdList.firstOrNull() ?: "0"
    }

    fun sensorOrientation(ctx: Context, cameraId: String): Int =
        mgr(ctx).getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /** Small 16:9 preview size (max 1280x720) so the preview never slows down 4K recording. */
    fun previewSize(ctx: Context, cameraId: String): Size {
        return try {
            val map = mgr(ctx).getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)
            val maxArea = 1280 * 720
            sizes.filter { it.width * 9 == it.height * 16 && it.width * it.height <= maxArea }
                .maxByOrNull { it.width * it.height }
                ?: sizes.filter { it.width * it.height <= maxArea }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: Size(1280, 720)
        } catch (e: Exception) {
            Size(1280, 720)
        }
    }

    private fun hevcOk(w: Int, h: Int, fps: Int): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                    (info.getCapabilitiesForType("video/hevc").videoCapabilities
                        ?.areSizeAndRateSupported(w, h, fps.toDouble()) ?: false)
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun bitrate(width: Int, fps: Int, pct: Int): Int {
        val base = when {
            width >= 3840 -> if (fps >= 60) 48_000_000 else 32_000_000
            width >= 1920 -> if (fps >= 60) 20_000_000 else 12_000_000
            else -> if (fps >= 60) 10_000_000 else 6_000_000
        }
        return (base.toLong() * pct / 100).toInt()
    }

    /**
     * Lists recording modes. Modes verified by the hardware are normal;
     * unverified 60 fps modes are only included (flagged experimental) if [includeExperimental].
     */
    fun queryConfigs(ctx: Context, cameraId: String, includeExperimental: Boolean, bitratePct: Int): List<VideoConfig> {
        val ch = mgr(ctx).getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val recSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        val aeRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val ae60 = aeRanges.any { it.upper >= 60 }
        val hsSizes = map.highSpeedVideoSizes ?: emptyArray()

        val out = mutableListOf<VideoConfig>()
        val candidates = listOf(Size(3840, 2160) to "4K UHD", Size(1920, 1080) to "1080p FHD", Size(1280, 720) to "720p HD")

        for ((size, name) in candidates) {
            if (recSizes.none { it.width == size.width && it.height == size.height }) continue

            val minDur = try { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) } catch (e: Exception) { 0L }
            val sizeMaxFps = if (minDur > 0) 1_000_000_000.0 / minDur else 0.0

            out.add(VideoConfig("${size.width}x${size.height}_30", size, 30, bitrate(size.width, 30, bitratePct), "$name • 30 fps"))

            val verified60 = ae60 && sizeMaxFps >= 59.0 && hevcOk(size.width, size.height, 60)
            if (verified60) {
                out.add(VideoConfig("${size.width}x${size.height}_60", size, 60, bitrate(size.width, 60, bitratePct), "$name • 60 fps"))
            } else if (includeExperimental) {
                out.add(
                    VideoConfig(
                        "${size.width}x${size.height}_60", size, 60, bitrate(size.width, 60, bitratePct),
                        "$name • 60 fps (not reported by device)", experimental = true
                    )
                )
            }

            if (hsSizes.any { it.width == size.width && it.height == size.height }) {
                val hsFps = try {
                    map.getHighSpeedVideoFpsRangesFor(size).map { it.upper }.distinct()
                } catch (e: Exception) {
                    emptyList()
                }
                for (fps in listOf(120, 240)) {
                    if (hsFps.contains(fps)) {
                        out.add(
                            VideoConfig(
                                "${size.width}x${size.height}_${fps}_hs", size, fps, bitrate(size.width, fps, bitratePct),
                                "$name • $fps fps high-speed", isHighSpeed = true
                            )
                        )
                    }
                }
            }
        }
        return out.sortedWith(compareByDescending<VideoConfig> { it.resolution.width }.thenByDescending { it.fps })
    }
}
