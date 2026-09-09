package kr.co.tesla.cameraalert.monitor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.tesla.cameraalert.MainActivity

/** Brief, dismissible floating speed-camera badge. */
object CameraAlertOverlay {
    private const val AUTO_DISMISS_MS = 12_000L
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var badge: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { hide() }

    fun show(context: Context, distanceMeters: Int, limitKph: Int?) {
        if (!Settings.canDrawOverlays(context)) return
        handler.removeCallbacks(autoDismiss)
        val text = badge ?: createBadge(context) ?: return
        text.text = "${limitKph?.toString() ?: "속도"}\n${distanceMeters.coerceAtLeast(0)}m"
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    fun hide() {
        handler.removeCallbacks(autoDismiss)
        val view = overlay ?: return
        runCatching { windowManager?.removeView(view) }
        overlay = null
        badge = null
        windowManager = null
    }

    private fun createBadge(context: Context): TextView? {
        val label = TextView(context.applicationContext).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        }
        val close = TextView(context.applicationContext).apply {
            text = "×"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), 0, dp(context, 12), 0)
            contentDescription = "플로팅 알림 닫기"
            setOnClickListener { hide() }
        }
        val view = LinearLayout(context.applicationContext).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(190, 42, 42))
                cornerRadius = dp(context, 28).toFloat()
                setStroke(dp(context, 2), Color.WHITE)
            }
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(context, 16); y = dp(context, 120) }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val dragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = layout.x; startY = layout.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) moved = true
                    if (moved) {
                        // END gravity measures x from the right edge, so horizontal movement is inverted.
                        layout.x = (startX - dx).toInt().coerceAtLeast(0)
                        layout.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                else -> true
            }
        }
        view.setOnTouchListener(dragListener)
        label.setOnTouchListener(dragListener)
        return try {
            context.getSystemService(WindowManager::class.java).also { manager ->
                manager.addView(view, layout)
                windowManager = manager
                overlay = view
                badge = label
            }
            label
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
