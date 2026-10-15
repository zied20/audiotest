package com.fersi.audiotest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "START" -> if (!running) startCapture(i)
            "STOP" -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun toast(t: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    private fun startCapture(i: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("يسجل صوت الجهاز")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra("data")
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data!!)
            mp.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
            projection = mp
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val r = AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(32000).setAudioPlaybackCaptureConfig(cfg).build()
            rec = r
            running = true
            worker = Thread {
                val f = File(getExternalFilesDir(null), "capture.wav")
                val out = RandomAccessFile(f, "rw")
                out.setLength(0); out.write(ByteArray(44))
                val buf = ByteArray(3200)
                var total = 0L
                var peak = 0
                r.startRecording()
                while (running) {
                    val len = r.read(buf, 0, buf.size)
                    if (len > 0) {
                        out.write(buf, 0, len); total += len
                        var j = 0
                        while (j + 1 < len) {
                            val v = abs(((buf[j + 1].toInt() shl 8) or (buf[j].toInt() and 0xff)).toShort().toInt())
                            if (v > peak) peak = v
                            j += 2
                        }
                    }
                }
                r.stop(); r.release()
                header(out, total); out.close()
                toast("الحجم: ${total / 1024} KB | أعلى مستوى صوت: $peak من 32767")
            }.also { it.start() }
        } catch (e: Exception) {
            toast("فشل: ${e.message}")
            stopSelf()
        }
    }

    private fun stopCapture() {
        running = false
        worker?.join(3000)
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun header(o: RandomAccessFile, len: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + len).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(len.toInt())
        o.seek(0); o.write(b.array())
    }
}
