package com.example.cam2vault.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cam2vault.data.MediaStoreVideoItem
import com.example.cam2vault.data.Recorder
import com.example.cam2vault.service.VideoRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val state by Recorder.state.collectAsState()
    var items by remember { mutableStateOf<List<MediaStoreVideoItem>>(emptyList()) }

    LaunchedEffect(resumeTick, state.isRecording) {
        items = withContext(Dispatchers.IO) { queryVideos(ctx) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "تسجيلاتي (${items.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        if (items.isEmpty()) {
            Text(
                text = "لا توجد تسجيلات بعد. التسجيلات تُحفظ في Movies/Cam2Vault.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items) { v ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(v.uri, "video/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "لا يوجد تطبيق لفتح الفيديو", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(v.displayName, fontSize = 14.sp)
                    Row {
                        Text(
                            text = "${v.resolution}  •  ${VideoRecordingService.formatTime(v.durationMs)}  •  ${formatBytes(v.sizeBytes)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = DateFormat.getDateTimeInstance().format(Date(v.dateAddedMs)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun queryVideos(ctx: android.content.Context): List<MediaStoreVideoItem> {
    val out = mutableListOf<MediaStoreVideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT
    )
    try {
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Movies/Cam2Vault%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    MediaStoreVideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = c.getString(nameCol) ?: "video",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedMs = c.getLong(dateCol) * 1000,
                        resolution = "${c.getInt(wCol)}x${c.getInt(hCol)}"
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("LibraryScreen", "query failed", e)
    }
    return out
}
