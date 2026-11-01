package com.example.cam2vault.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import kotlin.math.abs

class FloatingPreviewManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var currentSurface: Surface? = null
    var isAttached = false
        private set

    // 0: Small (160x90 dp), 1: Medium (240x135 dp), 2: Large (320x180 dp)
    private var currentSizeIndex = 0

    var onRecordClick: (() -> Unit)? = null
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingPreview() {
        if (isAttached || !canDrawOverlays()) return

        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()
        }

        val dimensions = listOf(
            Pair(dpToPx(160f), dpToPx(90f)),
            Pair(dpToPx(240f), dpToPx(135f)),
            Pair(dpToPx(320f), dpToPx(180f))
        )

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (initW, initH) = dimensions[currentSizeIndex]

        val params = WindowManager.LayoutParams(
            initW, initH,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20f)
            y = dpToPx(120f)
        }

        val card = CardView(context).apply {
            radius = dpToPx(12f).toFloat()
            cardElevation = dpToPx(8f).toFloat()
            setCardBackgroundColor(Color.BLACK)
        }

        val rootFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val surface = Surface(st)
                    currentSurface = surface
                    onSurfaceReady?.invoke(surface)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceDestroyed?.invoke()
                    currentSurface?.release()
                    currentSurface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        rootFrame.addView(textureView)

        // HUD علوي مع زرار للتحكم في الحجم بضغطة زرار
        val hudLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }

        val sizeBtn = TextView(context).apply {
            text = "⛶ الحجم"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, dpToPx(8f), 0)
            setOnClickListener {
                currentSizeIndex = (currentSizeIndex + 1) % dimensions.size
                val (newW, newH) = dimensions[currentSizeIndex]
                params.width = newW
                params.height = newH
                windowManager.updateViewLayout(overlayView, params)
            }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { hideFloatingPreview() }
        }

        hudLayout.addView(sizeBtn)
        hudLayout.addView(closeBtn)
        rootFrame.addView(hudLayout)
        card.addView(rootFrame)
        overlayView = card

        // السحب والتحريك في أي مكان على الشاشة
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX < 10 && deltaY < 10) {
                        onRecordClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(overlayView, params)
            isAttached = true
        } catch (e: Exception) {
            Log.e("FloatingPreviewManager", "Error adding overlay", e)
        }
    }

    fun hideFloatingPreview() {
        if (!isAttached || overlayView == null) return
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.e("FloatingPreviewManager", "Error removing overlay", e)
        } finally {
            isAttached = false
            overlayView = null
            textureView = null
            currentSurface?.release()
            currentSurface = null
        }
    }
}
