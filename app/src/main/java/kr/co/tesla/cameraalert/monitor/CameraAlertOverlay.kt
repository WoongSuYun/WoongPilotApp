package kr.co.tesla.cameraalert.monitor

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.tesla.cameraalert.MainActivity

/** A draggable, dismissible floating speed-camera sign with the distance shown underneath. */
object CameraAlertOverlay {
    private const val AUTO_DISMISS_MS = 12_000L
    private const val POSITION_PREFS = "camera_alert_overlay"
    private const val POSITION_X = "position_x"
    private const val POSITION_Y = "position_y"

    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var badge: CameraAlertBadge? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { hide() }

    fun show(context: Context, distanceMeters: Int, limitKph: Int?, keepVisible: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) return
        handler.removeCallbacks(autoDismiss)
        val current = badge ?: createBadge(context) ?: return
        current.update(distanceMeters, limitKph)
        if (!keepVisible) handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    fun hide() {
        handler.removeCallbacks(autoDismiss)
        val view = overlay ?: return
        runCatching { windowManager?.removeView(view) }
        overlay = null
        badge = null
        windowManager = null
    }

    private fun createBadge(context: Context): CameraAlertBadge? {
        val current = CameraAlertBadge(context.applicationContext)
        val positionPrefs = context.applicationContext.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = positionPrefs.getInt(POSITION_X, dp(context, 16))
            y = positionPrefs.getInt(POSITION_Y, dp(context, 120))
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        current.setOnTouchListener { _, event ->
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
                        runCatching { windowManager?.updateViewLayout(current, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        positionPrefs.edit().putInt(POSITION_X, layout.x).putInt(POSITION_Y, layout.y).apply()
                    } else {
                        context.startActivity(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    true
                }
                else -> true
            }
        }
        return try {
            context.getSystemService(WindowManager::class.java).also { manager ->
                manager.addView(current, layout)
                windowManager = manager
                overlay = current
                badge = current
            }
            current
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class CameraAlertBadge(context: Context) : FrameLayout(context) {
        private val sign = SpeedLimitSignView(context)
        private val distance = TextView(context)

        init {
            minimumWidth = dp(context, 98)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(context, 16).toFloat()
                setStroke(dp(context, 1), Color.rgb(220, 220, 220))
            }
            elevation = dp(context, 6).toFloat()

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(sign, LinearLayout.LayoutParams(dp(context, 78), dp(context, 78)))
            }
            distance.apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(190, 42, 42))
                    cornerRadius = dp(context, 14).toFloat()
                }
            }
            content.addView(distance, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 30)))
            addView(content, LayoutParams(dp(context, 78), LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(context, 10)
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 10)
            })

            val close = TextView(context).apply {
                text = "X"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(85, 85, 85))
                gravity = Gravity.CENTER
                contentDescription = "Close speed camera alert"
                setOnClickListener { hide() }
            }
            addView(close, LayoutParams(dp(context, 18), dp(context, 18), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(context, 2)
                rightMargin = dp(context, 2)
            })
        }

        fun update(distanceMeters: Int, limitKph: Int?) {
            sign.setSpeedLimit(limitKph)
            distance.text = "${distanceMeters.coerceAtLeast(0)}m"
        }
    }

    /** Korean-style speed-limit sign with the applicable enforcement limit in the centre. */
    private class SpeedLimitSignView(context: Context) : View(context) {
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 35, 45) }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 25) }
        private var speedLimit = "--"

        fun setSpeedLimit(limitKph: Int?) {
            speedLimit = limitKph?.coerceAtLeast(1)?.toString() ?: "--"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val center = width / 2f
            val radius = (width.coerceAtMost(height) / 2f) - 4f
            canvas.drawCircle(center, height / 2f, radius, red)
            canvas.drawCircle(center, height / 2f, radius - 7f, white)
            black.apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = if (speedLimit.length <= 2) radius * .92f else radius * .68f
            }
            val baseline = height / 2f - (black.ascent() + black.descent()) / 2f
            canvas.drawText(speedLimit, center, baseline, black)
        }
    }
}
