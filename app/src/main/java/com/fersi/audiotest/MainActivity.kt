package com.fersi.audiotest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "1) ابدأ التسجيل  2) شغّل فيديو في تطبيق آخر  3) ارجع وأوقف"
            textSize = 18f
        }
        val start = Button(this).apply { text = "ابدأ التسجيل"; setOnClickListener { begin() } }
        val stop = Button(this).apply {
            text = "أوقف واحفظ"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction("STOP"))
                status.text = "تم الإيقاف. الملف: Android/data/com.fersi.audiotest/files/capture.wav"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status); addView(start); addView(stop)
        })
    }

    private fun begin() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else askCapture()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askCapture()
        else status.text = "لازم تعطي إذن الميكروفون"
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 2 && res == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction("START").putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            status.text = "يسجل الآن... شغّل الفيديو"
        } else if (req == 2) status.text = "تم رفض الإذن"
    }
}
