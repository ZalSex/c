package com.cleanser.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class AppBlockerService : AccessibilityService() {

    private val handler    = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var wm: WindowManager?    = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == applicationContext.packageName) return
        if (pkg == "com.android.systemui") return

        val prefs   = getSharedPreferences("cleanser", Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        if (pkg !in blocked) return

        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 80)
        handler.postDelayed({
            showBlockNotif()
        }, 300)
    }

    private fun showBlockNotif() {
        removeNotif()
        val canDraw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        if (!canDraw) return

        try {
            val dm   = resources.displayMetrics
            val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER
                setPadding(
                    (36 * dm.density).toInt(), (28 * dm.density).toInt(),
                    (36 * dm.density).toInt(), (28 * dm.density).toInt()
                )
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE1a1a2e"))
                    cornerRadius = 20 * dm.density
                    setStroke((2 * dm.density).toInt(), Color.parseColor("#EF4444"))
                }
                background = bg
            }

            val tvIcon = TextView(this).apply {
                text     = "🚫"
                textSize = 40f
                gravity  = Gravity.CENTER
            }
            card.addView(tvIcon)

            val tv1 = TextView(this).apply {
                text     = "Aplikasi Ini Diblokir"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity  = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(tv1, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dm.density).toInt() })

            val tv2 = TextView(this).apply {
                text     = "By Zal7Sex"
                textSize = 13f
                setTextColor(Color.parseColor("#FFAAAAAA"))
                gravity  = Gravity.CENTER
            }
            card.addView(tv2, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dm.density).toInt() })

            val flp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }

            container.addView(card, flp)
            overlay = container
            wm?.addView(container, lp)
            handler.postDelayed({ removeNotif() }, 2500)
        } catch (_: Exception) {}
    }

    private fun removeNotif() {
        overlay?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeNotif()
    }
}
