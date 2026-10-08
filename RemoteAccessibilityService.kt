package id.arunika.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Explicitly enabled by the owner in Android Accessibility Settings. */
class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
        private const val CHANNEL = "arunika_remote_active"
        private const val NOTIF = 101
    }
    @Volatile var stateLabel = "Memulai ..."
        private set
    @Volatile var serverAddress = ""
        private set
    @Volatile private var lastImage: ByteArray? = null
    @Volatile private var lastViewerAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var server: PrivateHttpServer? = null
    private var active = false
    private var screenshotBusy = false
    private var wasViewing = false
    private val monitor = object : Runnable {
        override fun run() {
            if (!active) return
            val tailIp = RemoteConfig.tailscaleIp()?.hostAddress
            if (tailIp.orEmpty() != serverAddress) startOrRefreshServer()
            val isViewing = System.currentTimeMillis() - lastViewerAt < 8000
            if (isViewing != wasViewing) { wasViewing = isViewing; updateNotification() }
            handler.postDelayed(this, 4500)
        }
    }
    private val frameLoop = object : Runnable {
        override fun run() {
            if (!active) return
            if (server != null && System.currentTimeMillis() - lastViewerAt < 8000 && !screenshotBusy) captureFrame()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this; applySettings() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        stopRemote(); if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        stopRemote(); if (instance === this) instance = null
        worker.shutdown(); super.onDestroy()
    }

    fun applySettings() {
        handler.post {
            if (!RemoteConfig.enabled(this)) { stopRemote(); return@post }
            val notifications = getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled()) {
                stopRemote(); stateLabel = "Aktifkan izin notifikasi"; return@post
            }
            if (active) { startOrRefreshServer(); return@post }
            active = true
            startOrRefreshServer()
            handler.post(monitor)
            handler.post(frameLoop)
        }
    }

    private fun startOrRefreshServer() {
        server?.stop(); server = null; serverAddress = ""; lastImage = null
        val ip = RemoteConfig.tailscaleIp()
        if (!active) return
        if (ip == null) {
            stateLabel = "Menunggu Tailscale aktif"
        } else {
            try {
                server = PrivateHttpServer(this, ip, RemoteConfig.token(this)).also { it.start() }
                serverAddress = ip.hostAddress ?: ""
                stateLabel = "Siap • http://$serverAddress:${PrivateHttpServer.PORT}"
            } catch (_: Exception) { stateLabel = "Gagal membuka layanan lokal" }
        }
        updateNotification()
    }

    private fun stopRemote() {
        active = false
        handler.removeCallbacks(frameLoop); handler.removeCallbacks(monitor)
        server?.stop(); server = null; serverAddress = ""
        lastImage = null; lastViewerAt = 0
        wasViewing = false; screenshotBusy = false
        stateLabel = "Nonaktif"
        getSystemService(NotificationManager::class.java).cancel(NOTIF)
    }

    fun recordViewer() { lastViewerAt = System.currentTimeMillis() }
    fun latestFrame(): ByteArray? = lastImage

    fun validAction(data: JSONObject): Boolean {
        return when (data.optString("action")) {
            "back", "home", "recents", "notifications" -> true
            "tap", "swipe" -> {
                val x = data.optDouble("x", Double.NaN)
                val y = data.optDouble("y", Double.NaN)
                val isSwipe = data.optString("action") == "swipe"
                val x2 = data.optDouble("x2", Double.NaN)
                val y2 = data.optDouble("y2", Double.NaN)
                x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 &&
                    (!isSwipe || (x2.isFinite() && y2.isFinite() && x2 in 0.0..1.0 && y2 in 0.0..1.0))
            }
            else -> false
        }
    }

    fun performRemoteAction(data: JSONObject) {
        if (!active || !validAction(data)) return
        when (data.optString("action")) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "tap", "swipe" -> {
                val point = Point()
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
                val w = max(1, point.x - 1).toFloat()
                val h = max(1, point.y - 1).toFloat()
                val x = data.optDouble("x")
                val y = data.optDouble("y")
                val gesture = Path().apply {
                    moveTo((x * w).toFloat(), (y * h).toFloat())
                    if (data.optString("action") == "swipe")
                        lineTo((data.optDouble("x2") * w).toFloat(), (data.optDouble("y2") * h).toFloat())
                }
                val time = if (data.optString("action") == "tap") 90L else 420L
                dispatchGesture(GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(gesture, 0, time)).build(), null, null)
            }
        }
    }

    private fun captureFrame() {
        screenshotBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, java.util.concurrent.Executor { command -> handler.post(command) },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) { screenshotBusy = false }
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        worker.execute {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: throw IllegalStateException("Buffer not supported")
                                val width = min(540, bitmap.width)
                                val height = max(1, bitmap.height * width / bitmap.width)
                                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                                val out = ByteArrayOutputStream()
                                try { scaled.compress(Bitmap.CompressFormat.JPEG, 55, out) }
                                finally {
                                    if (scaled !== bitmap) scaled.recycle()
                                    bitmap.recycle()
                                }
                                lastImage = out.toByteArray()
                            } catch (_: Exception) { /* capture may be blocked on secure screens */ }
                            finally { buffer.close(); handler.post { screenshotBusy = false } }
                        }
                    }
                })
        } catch (_: Exception) { screenshotBusy = false }
    }

    private fun updateNotification() {
        if (!active) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Akses Arunika Remote", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(this, 0,
            Intent(this, RemoteStopReceiver::class.java).setAction(RemoteStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(if (wasViewing) "Arunika Remote • layar sedang diakses" else "Arunika Remote aktif")
            .setContentText(stateLabel)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "HENTIKAN", stop)
            .build()
        try { manager.notify(NOTIF, notification) }
        catch (_: SecurityException) { stopRemote() }
    }
}
