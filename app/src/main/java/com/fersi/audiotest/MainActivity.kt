package com.fersi.audiotest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var groq: EditText
    private lateinit var claude: EditText
    private lateinit var useClaude: CheckBox
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        fun field(h: String, k: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(k, ""))
        }
        fun link(t: String, u: String) = Button(this).apply {
            text = t
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        }
        groq = field("Groq API key", "groq")
        claude = field("Anthropic API key (اختياري - ترجمة أفضل، مدفوع)", "claude")
        useClaude = CheckBox(this).apply {
            text = "استخدم Claude للترجمة (أدق، وقد يكون أبطأ). ألغِ التحديد للترجمة المجانية السريعة"
            isChecked = prefs.getBoolean("useClaude", true)
        }
        val start = Button(this).apply { text = "ابدأ الترجمة"; setOnClickListener { begin() } }
        val stop = Button(this).apply {
            text = "إيقاف"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction("STOP"))
                status.text = "تم الإيقاف"
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(link("احصل على مفتاح Groq (تحويل الصوت لنص)", "https://console.groq.com/keys"))
            addView(groq)
            addView(link("مفتاح Anthropic (اختياري)", "https://console.anthropic.com/settings/keys"))
            addView(claude)
            addView(useClaude)
            addView(start); addView(stop)
        }
        setContentView(ScrollView(this).apply { addView(box) })
        handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    private fun handle(i: Intent) {
        when (i.getStringExtra("bad")) {
            "groq" -> { groq.setText(""); status.text = "مفتاح Groq مرفوض. أدخل مفتاحاً جديداً ثم اضغط ابدأ" }
            "claude" -> { claude.setText(""); status.text = "مفتاح Anthropic مرفوض. أدخل مفتاحاً جديداً أو اتركه فارغاً للترجمة المجانية ثم اضغط ابدأ" }
            else -> status.text =
                if (groq.text.isEmpty())
                    "أول تشغيل: يلزمك مفتاح Groq المجاني فقط. أنشئه من الزر أدناه ثم الصقه. (مفتاح Anthropic اختياري)"
                else "جاهز (" + (if (claude.text.isEmpty()) "ترجمة مجانية" else "ترجمة Claude") + ")"
        }
    }

    private fun check(url: String, vararg h: Pair<String, String>): Int = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        h.forEach { c.setRequestProperty(it.first, it.second) }
        c.responseCode
    } catch (e: Exception) { -1 }

    private fun begin() {
        val g = groq.text.toString().trim()
        val c = claude.text.toString().trim()
        if (g.isEmpty()) { status.text = "أدخل مفتاح Groq"; return }
        status.text = "جاري التحقق من المفاتيح..."
        Thread {
            val gc = check("https://api.groq.com/openai/v1/models", "Authorization" to "Bearer $g")
            val cc = if (c.isEmpty()) 200 else check("https://api.anthropic.com/v1/models", "x-api-key" to c, "anthropic-version" to "2023-06-01")
            runOnUiThread {
                when {
                    gc == -1 || cc == -1 -> status.text = "تعذر الاتصال. تحقق من الإنترنت"
                    gc != 200 -> status.text = "مفتاح Groq غير صالح"
                    cc != 200 -> status.text = "مفتاح Anthropic غير صالح"
                    else -> {
                        prefs.edit().putString("groq", g).putString("claude", c).putBoolean("useClaude", useClaude.isChecked).apply()
                        permissions()
                    }
                }
            }
        }.start()
    }

    private fun permissions() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "فعّل (العرض فوق التطبيقات) ثم ارجع واضغط ابدأ مرة ثانية"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
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
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction("START").putExtra("code", res).putExtra("data", data)
            )
            status.text = "يعمل... افتح أي فيديو"
        } else if (req == 2) status.text = "تم رفض الإذن"
    }
}
