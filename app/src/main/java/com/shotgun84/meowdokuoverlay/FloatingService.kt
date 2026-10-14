package com.shotgun84.meowdokuoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer

/** Floating cat button -> one-shot screenshot -> solve -> in-game cat overlay. */
class FloatingService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_RESULT_DATA = "data"
        const val CHANNEL = "solver"
    }

    private lateinit var wm: WindowManager
    private var buttonView: View? = null
    private var resultView: FrameLayout? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startAsForeground()
            val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(rc, data!!)
            showButton()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        nb.setContentTitle("Meowdoku solver running")
            .setContentText("Tap the floating cat inside the game")
            .setSmallIcon(android.R.drawable.btn_star)
        startForeground(1, nb.build())
    }

    private fun overlayParams(x: Int = 0, y: Int = 200, w: Int = WindowManager.LayoutParams.WRAP_CONTENT, h: Int = WindowManager.LayoutParams.WRAP_CONTENT) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun showButton() {
        wm = getSystemService(WindowManager::class.java)
        if (buttonView != null) return
        val btn = TextView(this).apply {
            text = "\uD83D\uDC31" // cat face, rendered natively by Android
            textSize = 40f
            setOnClickListener { captureAndSolve() }
        }
        var dx = 0; var dy = 0; var sx = 0; var sy = 0
        btn.setOnTouchListener { v, e ->
            val p = v.layoutParams as WindowManager.LayoutParams
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = p.x; dy = p.y; sx = e.rawX.toInt(); sy = e.rawY.toInt(); false }
                MotionEvent.ACTION_MOVE -> {
                    p.x = dx + (e.rawX.toInt() - sx)
                    p.y = dy + (e.rawY.toInt() - sy)
                    wm.updateViewLayout(v, p)
                    true
                }
                else -> false
            }
        }
        buttonView = btn
        wm.addView(btn, overlayParams())
    }

    private fun captureAndSolve() {
        toast("Capturing board…")
        Thread {
            try {
                val bitmap = grabScreen() ?: throw IllegalStateException("capture failed")
                val board = Vision.extractBoard(bitmap)
                val sol = Solver.solve(board.regions)
                    ?: throw IllegalStateException("no solution found")
                ui.post { showResult(board, sol) }
            } catch (t: Throwable) {
                ui.post { toast("Solve failed: ${t.message}") }
            }
        }.start()
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun grabScreen(): Bitmap? {
        releaseCapture()
        val (w, h) = screenSize()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "meowdoku", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null,
        )
        // Give the compositor a moment to deliver a frame.
        Thread.sleep(400)
        val img = imageReader!!.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPad = rowStride - pixelStride * w
            val padded = Bitmap.createBitmap(w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buf)
            return Bitmap.createBitmap(padded, 0, 0, w, h)
        } finally {
            img.close()
        }
    }

    private fun releaseCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    private fun showResult(board: Vision.BoardResult, sol: List<Solver.Cell>) {
        dismissResult()
        val frame = FrameLayout(this).apply {
            setOnClickListener { dismissResult() } // tap anywhere to dismiss
        }
        val (bx, by) = board.bbox.x to board.bbox.y
        for ((r, c) in sol) {
            val (x1, x2) = board.cellsX[c]
            val (y1, y2) = board.cellsY[r]
            val cx = bx + (x1 + x2) / 2
            val cy = by + (y1 + y2) / 2
            val size = minOf(x2 - x1, y2 - y1)
            val cat = TextView(this).apply {
                text = "\uD83D\uDC31"
                // Raw px: screenshot px == screen px since capture is display-sized.
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.80f)
                gravity = Gravity.CENTER
            }
            val lp = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = cx - size / 2
                topMargin = cy - size / 2
            }
            frame.addView(cat, lp)
        }
        resultView = frame
        wm.addView(
            frame,
            overlayParams(0, 0, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT),
        )
        toast("${sol.size} cats — tap board to dismiss")
    }

    private fun dismissResult() {
        resultView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultView = null
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        dismissResult()
        buttonView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        releaseCapture()
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
