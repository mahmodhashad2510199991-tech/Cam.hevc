package com.example.cam2vault.data

import android.net.Uri
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow

/** A recording mode (resolution + fps) offered to the user. */
data class VideoConfig(
    val id: String,
    val resolution: Size,
    val fps: Int,
    val bitrate: Int,
    val label: String,
    val codec: String = "HEVC (H.265)",
    val isHighSpeed: Boolean = false,
    /** true = the device does not report this mode; we try it and fall back automatically if it fails. */
    val experimental: Boolean = false
) {
    val formattedBitrate: String get() = "${bitrate / 1_000_000} Mbps"
    val is4K: Boolean get() = resolution.width >= 3840
    val shortName: String
        get() {
            val n = when {
                resolution.width >= 3840 -> "4K"
                resolution.width >= 1920 -> "1080p"
                else -> "720p"
            }
            return if (isHighSpeed) "$n ${fps}fps ⚡" else "$n $fps"
        }
}

data class CameraInfo(val id: String, val label: String, val facing: Int)

data class RecordingState(
    val isRecording: Boolean = false,
    val countdown: Int = 0,
    val elapsedMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val activeConfig: VideoConfig? = null,
    val fileName: String? = null,
    val message: String? = null
)

/** Global recording state shared by the service, the overlay and the UI. */
object Recorder {
    val state = MutableStateFlow(RecordingState())
}

data class MediaStoreVideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val resolution: String
)
