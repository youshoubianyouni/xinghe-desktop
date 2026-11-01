package app.xinghe.tv.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The floating window that carries the traffic reading over every app, not just Home.
 *
 * It is the window 星河守卫 shows, told again: a bold white line with a strong shadow and no
 * background, pinned by the system's own [WindowManager] to whichever corner was chosen — the
 * system does the fitting to every set, no measuring of our own. The window takes no touches and
 * no focus, so the app under it never feels it.
 *
 * Compatibility notes, learned on 星河守卫's firmwares:
 * 1. [Settings.canDrawOverlays] lies on some boxes (permission held, still false), so it is only
 *    a hint for the settings page, never a gate — the window is simply tried;
 * 2. a few firmwares check the window token of [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 *    oddly, so the type walks a chain — APPLICATION_OVERLAY, then PHONE, then SYSTEM_ALERT, then
 *    SYSTEM_OVERLAY — and the first one that sticks wins;
 * 3. when nothing sticks the service quits quietly; the reading stays on Home the plain way.
 */
class TrafficFloatService : Service() {

    companion object {
        const val ACTION_STOP = "app.xinghe.tv.traffic.FLOAT_STOP"
        private const val CHANNEL_ID = "traffic_float"

        /** A hint for the settings page; some firmwares report this wrongly, so not a gate. */
        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val i = Intent(context, TrafficFloatService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (t: Throwable) { /* the system said no; the reading stays on Home */ }
        }

        /** The system's page for the overlay permission (some TV firmware has no path there). */
        fun openOverlaySettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) { /* nowhere to go on this TV */ }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, TrafficFloatService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(notifyId(), buildNotification())
        // The sampler is what makes the numbers; the switch may have been left on from before.
        TrafficMonitor.start(this)
        if (!TrafficMonitor.on.value) {
            // Asked to show while the switch is off: nothing to watch, leave.
            stopSelf()
            return START_NOT_STICKY
        }
        createWindow()
        handler.post(tick)
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            // No background at all: white letters over a strong shadow, readable on anything.
            background = null
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            includeFontPadding = false
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }

        // The chain of window types: whichever the firmware lets stick.
        val types = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            types += WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            types += WindowManager.LayoutParams.TYPE_PHONE
        }
        @Suppress("DEPRECATION")
        types += WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        @Suppress("DEPRECATION")
        types += WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        var added = false
        for (type in types) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = cornerGravity()
            params.x = dp(24)
            params.y = dp(24)
            try {
                wm?.addView(view, params)
                lp = params
                added = true
                break
            } catch (t: Throwable) { /* this type would not stick; try the next */ }
        }

        if (!added) stopSelf()
    }

    private var lastCorner = -1

    private fun update() {
        val v = view ?: return
        applyCorner()
        val s = TrafficMonitor.speed.value
        v.text = "↑ ${TrafficMonitor.rate(s.tx)}  ↓ ${TrafficMonitor.rate(s.rx)}"
    }

    /** The gravity of the corner the setting in Control Center picked. */
    private fun cornerGravity(): Int = when (TrafficMonitor.corner.value) {
        TrafficMonitor.Corner.TopEnd -> Gravity.TOP or Gravity.END
        TrafficMonitor.Corner.BottomStart -> Gravity.BOTTOM or Gravity.START
        TrafficMonitor.Corner.BottomEnd -> Gravity.BOTTOM or Gravity.END
        else -> Gravity.TOP or Gravity.START
    }

    /** Applies the corner setting; a change in Control Center lands within the second. */
    private fun applyCorner() {
        val v = view ?: return
        val manager = wm ?: return
        val params = lp ?: return
        val corner = TrafficMonitor.corner.value.ordinal
        if (corner == lastCorner) return
        params.gravity = cornerGravity()
        params.x = dp(24)
        params.y = dp(24)
        try {
            manager.updateViewLayout(v, params)
            lastCorner = corner
        } catch (t: Throwable) {
            // Not attached yet, or the window is gone; the next tick tries again.
        }
    }

    private fun notifyId(): Int = 0x5452 // 'TR'

    private fun buildNotification(): Notification {
        // A quiet, ongoing notice, as a foreground service must carry. On a TV it is unseen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Traffic", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Traffic")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            wm?.removeView(view)
        } catch (t: Throwable) { /* already gone */ }
        view = null
        lp = null
        wm = null
        super.onDestroy()
    }
}
