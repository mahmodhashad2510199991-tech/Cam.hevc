package com.example.cam2vault.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.cam2vault.data.AppSettings
import kotlin.math.abs

/**
 * Small floating camera preview shown over other apps.
 * - drag with one finger, pinch with two fingers to resize
 * - tap to show the buttons: smaller / record-stop / bigger / hide
 */
class FloatingPreviewManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var recordBtn: TextView? = null
    private var timerText: TextView? = null
    private var ownedSurface: Surface? = null

    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null
    var onToggleRecord: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private val hideControlsRunnable = Runnable { controls?.visibility = View.GONE }

    private fun roundBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun circleButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA000000.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { setMargins(dp(3f), 0, dp(3f), 0) }
            setOnClickListener {
                onClick()
                scheduleHideControls()
            }
        }

    private fun scheduleHideControls() {
        main.removeCallbacks(hideControlsRunnable)
        main.postDelayed(hideControlsRunnable, 3500)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(previewSize: Size) {
        if (root != null || !canDrawOverlays()) return

        val minDp = MIN_DP
        val widthDp = AppSettings.overlayWidthDp(context).coerceIn(minDp, MAX_DP)
        val w = dp(widthDp.toFloat())
        val h = (w * 16f / 9f).toInt()

        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = AppSettings.overlayX(context, dp(16f))
            y = AppSettings.overlayY(context, dp(120f))
        }

        val frame = FrameLayout(context).apply {
            background = roundBg(Color.BLACK, 12f)
            clipToOutline = true
        }

        val texture = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    st.setDefaultBufferSize(previewSize.width, previewSize.height)
                    val s = Surface(st)
                    ownedSurface = s
                    onSurfaceReady?.invoke(s)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceDestroyed?.invoke()
                    ownedSurface?.release()
                    ownedSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        frame.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val timer = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            background = roundBg(0xAAE53935.toInt(), 8f)
            visibility = View.GONE
        }
        frame.addView(timer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(6f), dp(6f), 0, 0)
        })
        timerText = timer

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val rec = circleButton("●") { onToggleRecord?.invoke() }.apply { setTextColor(Color.RED) }
        recordBtn = rec
        bar.addView(circleButton("−") { resizeBy(0.85f) })
        bar.addView(rec)
        bar.addView(circleButton("+") { resizeBy(1.18f) })
        bar.addView(circleButton("✕") { onClose?.invoke() })
        frame.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(6f)
        })
        controls = bar

        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                resizeBy(detector.scaleFactor)
                return true
            }
        })

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        frame.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (scale.isInProgress) {
                moved = true
                return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    downRawX = ev.rawX; downRawY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1) {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                        if (moved) {
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
                            try { wm.updateViewLayout(frame, p) } catch (_: Exception) { }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        if (bar.visibility == View.VISIBLE) scheduleHideControls()
                    } else {
                        AppSettings.setOverlayPos(context, p.x, p.y)
                    }
                    true
                }
                else -> true
            }
        }

        try {
            wm.addView(frame, p)
            root = frame
            params = p
        } catch (e: Exception) {
            android.util.Log.e("FloatingPreview", "Cannot add overlay", e)
        }
    }

    private fun resizeBy(factor: Float) {
        val r = root ?: return
        val p = params ?: return
        val newW = (p.width * factor).toInt().coerceIn(dp(MIN_DP.toFloat()), dp(MAX_DP.toFloat()))
        p.width = newW
        p.height = (newW * 16f / 9f).toInt()
        try { wm.updateViewLayout(r, p) } catch (_: Exception) { }
        val dpValue = (newW / context.resources.displayMetrics.density).toInt()
        AppSettings.setOverlayWidthDp(context, dpValue)
    }

    fun updateStatus(recording: Boolean, timeText: String) {
        recordBtn?.text = if (recording) "■" else "●"
        timerText?.apply {
            text = "● $timeText"
            visibility = if (recording) View.VISIBLE else View.GONE
        }
    }

    fun hide() {
        main.removeCallbacks(hideControlsRunnable)
        val r = root ?: return
        try { wm.removeViewImmediate(r) } catch (_: Exception) { }
        root = null
        params = null
        controls = null
        recordBtn = null
        timerText = null
    }

    companion object {
        const val MIN_DP = 70
        const val MAX_DP = 260
    }
}
