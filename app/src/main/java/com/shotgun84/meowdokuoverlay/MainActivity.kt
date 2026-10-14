package com.shotgun84.meowdokuoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val info = TextView(this).apply { text = getString(R.string.how_to) }
        val btnOverlay = Button(this).apply { text = "1. Allow display over other apps" }
        val btnStart = Button(this).apply { text = "2. Allow screen capture + show cat button" }
        layout.addView(info)
        layout.addView(btnOverlay)
        layout.addView(btnStart)
        setContentView(layout)

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant display-over-apps first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
                return@setOnClickListener
            }
            startActivityForResult(projectionManager.createScreenCaptureIntent(), 42)
        }
    }

    @Deprecated("legacy onActivityResult still fine for a single capture intent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, FloatingService::class.java)
                .setAction(FloatingService.ACTION_START)
                .putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(FloatingService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            Toast.makeText(this, "Floating cat is up. Open Meowdoku and tap it.", Toast.LENGTH_LONG).show()
            finish()
        } else if (requestCode == 42) {
            Toast.makeText(this, "Screen capture denied, cannot solve", Toast.LENGTH_LONG).show()
        }
    }
}
