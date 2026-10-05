package com.lia.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : AppCompatActivity() {

    private class Item(val title: String, val status: String, val good: Boolean, val act: () -> Unit)

    private val micReq = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startVoice(); renderTab() }
    private val notifReq = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderTab() }

    private lateinit var body: FrameLayout
    private lateinit var sub: TextView
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var chatBox: LinearLayout? = null
    private var chatScroll: ScrollView? = null

    private val night: Boolean get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cBg: Int get() = Color.parseColor(if (night) "#0B0F1A" else "#F4F6FB")
    private val cCard: Int get() = Color.parseColor(if (night) "#161C2D" else "#FFFFFF")
    private val cText: Int get() = Color.parseColor(if (night) "#E8ECF8" else "#10131F")
    private val cSub: Int get() = Color.parseColor(if (night) "#8A93AD" else "#5B6478")
    private val cAccent: Int get() = Color.parseColor("#7C5CFF")
    private val cGood: Int get() = Color.parseColor("#2ECC71")
    private val cWarn: Int get() = Color.parseColor("#F5A623")

    private val listener: (String, String) -> Unit = { role, msg ->
        runOnUiThread {
            if (role == "status") sub.text = msg
            else if (tab == 0) addBubble(role, msg, true)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoice() {
        if (Store.serviceEnabled() && hasMic()) LiaService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(dp(20), dp(36), dp(20), dp(10))
        val title = TextView(this)
        title.text = "Lia"
        title.textSize = 26f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(cAccent)
        top.addView(title)
        sub = TextView(this)
        sub.text = "Say “Hey Lia”"
        sub.textSize = 13f
        sub.setTextColor(cSub)
        top.addView(sub)

        body = FrameLayout(this)

        nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(cCard)
        val names = listOf("Chat", "Keys", "Setup")
        for (i in names.indices) {
            val t = TextView(this)
            t.text = names[i]
            t.gravity = Gravity.CENTER
            t.textSize = 15f
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { tab = i; renderTab() }
            nav.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        root.addView(top)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(nav)
        setContentView(root)

        tab = if (Store.keys().isEmpty()) 1 else 0
        if (savedInstanceState == null && !hasMic()) micReq.launch(Manifest.permission.RECORD_AUDIO)
        renderTab()
        startVoice()
    }

    override fun onStart() { super.onStart(); Agent.listeners.add(listener) }
    override fun onStop() { Agent.listeners.remove(listener); super.onStop() }
    override fun onResume() { super.onResume(); startVoice(); renderTab() }

    private fun renderTab() {
        body.removeAllViews()
        chatBox = null
        chatScroll = null
        when (tab) {
            0 -> chatTab()
            1 -> keysTab()
            else -> setupTab()
        }
        for (i in 0 until nav.childCount) {
            val t = nav.getChildAt(i) as TextView
            t.setTextColor(if (i == tab) cAccent else cSub)
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ---------- small UI helpers ----------

    private fun bg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(r).toFloat()
        g.setColor(color)
        return g
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = bg(cCard, 16)
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(10)
        l.layoutParams = lp
        return l
    }

    private fun btn(t: String, primary: Boolean, click: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.gravity = Gravity.CENTER
        v.textSize = 14f
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(if (primary) Color.WHITE else cAccent)
        v.background = bg(if (primary) cAccent else Color.parseColor(if (night) "#232B45" else "#E9E6FF"), 12)
        v.setPadding(dp(16), dp(10), dp(16), dp(10))
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.topMargin = dp(8)
        lp.marginEnd = dp(8)
        v.layoutParams = lp
        v.setOnClickListener { click() }
        return v
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) r.addView(v)
        return r
    }

    // ---------- chat ----------

    private fun chatTab() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(8), dp(12), dp(8))
        val sc = ScrollView(this)
        sc.addView(box)
        chatBox = box
        chatScroll = sc
        val hist = Store.history().takeLast(30)
        if (hist.isEmpty()) addBubble("assistant", "Hi, I'm Lia. Say “Hey Lia” followed by a request, or type here.", false)
        for (m in hist) addBubble(m.optString("role"), m.optString("content"), false)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(8), dp(12), dp(8))
        bar.setBackgroundColor(cCard)
        val et = EditText(this)
        et.hint = "Ask Lia…"
        et.setTextColor(cText)
        et.setHintTextColor(cSub)
        et.setBackgroundColor(Color.TRANSPARENT)
        et.maxLines = 4
        val send = btn("Send", true) {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                et.setText("")
                Agent.ask(applicationContext, t)
            }
        }
        bar.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(send)
        wrap.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        wrap.addView(bar)
        body.addView(wrap, FrameLayout.LayoutParams(MATCH, MATCH))
        sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(role: String, msg: String, scroll: Boolean) {
        val box = chatBox ?: return
        val user = role == "user"
        val v = TextView(this)
        v.text = msg
        v.textSize = 15f
        v.setTextColor(if (user) Color.WHITE else cText)
        v.setPadding(dp(14), dp(10), dp(14), dp(10))
        v.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        v.background = bg(if (user) cAccent else cCard, 18)
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(6)
        box.addView(v, lp)
        if (scroll) chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- keys ----------

    private fun mask(k: String) = if (k.length <= 10) "••••••" else k.take(4) + "••••••••" + k.takeLast(4)

    private fun keysTab() {
        val sc = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(24))
        sc.addView(col)
        col.addView(tv("API KEYS  ·  Groq", 12f, cSub, true))
        val keys = Store.keys()
        if (keys.isEmpty()) col.addView(card().apply { addView(tv("No keys yet. Tap “+ Add API Key” and paste your Groq key.", 14f, cText, false)) })
        val now = System.currentTimeMillis()
        for (k in keys) {
            val c = card()
            c.addView(tv(k.label + "   " + (if (k.enabled) "● Active" else "○ Disabled"), 16f, cText, true))
            c.addView(tv(mask(k.key), 13f, cSub, false))
            if (k.enabled && k.cooldownUntil > now) c.addView(tv("Rate-limited, resting for a moment", 12f, cWarn, false))
            c.addView(row(
                btn(if (k.enabled) "Disable" else "Enable", false) { Store.toggleKey(k.id); renderTab() },
                btn("Delete", false) {
                    AlertDialog.Builder(this).setTitle("Delete ${k.label}?")
                        .setPositiveButton("Delete") { _, _ -> Store.deleteKey(k.id); renderTab() }
                        .setNegativeButton("Cancel", null).show()
                }
            ))
            col.addView(c)
        }
        col.addView(btn("+ Add API Key", true) { addKeyDialog() })

        col.addView(tv("MODEL", 12f, cSub, true).apply { setPadding(0, dp(20), 0, 0) })
        val mc = card()
        val et = EditText(this)
        et.setText(Store.model())
        et.setTextColor(cText)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        mc.addView(et)
        mc.addView(btn("Save model", false) { Store.setModel(et.text.toString()); sub.text = "Model saved" })
        col.addView(mc)
        col.addView(tv("Keys are encrypted on this phone and only sent to api.groq.com. Lia rotates between active keys and skips any that hit a rate limit.", 12f, cSub, false).apply { setPadding(0, dp(16), 0, 0) })
        body.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun addKeyDialog() {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(20), dp(8), dp(20), 0)
        val n = EditText(this)
        n.hint = "Label (e.g. Groq #1)"
        val k = EditText(this)
        k.hint = "Paste API key (gsk_…)"
        k.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        l.addView(n)
        l.addView(k)
        AlertDialog.Builder(this).setTitle("Add API key").setView(l)
            .setPositiveButton("Add") { _, _ ->
                if (k.text.isNotBlank()) {
                    Store.addKey(n.text.toString(), k.text.toString())
                    renderTab()
                    startVoice()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- setup ----------

    private fun setupTab() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val accOk = LiaAccessibility.instance != null
        val overlayOk = Settings.canDrawOverlays(this)
        val writeOk = Settings.System.canWrite(this)
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        val svcOn = Store.serviceEnabled()
        val pkg = Uri.parse("package:$packageName")

        val items = listOf(
            Item("1. Microphone  (for “Hey Lia”)", if (hasMic()) "Granted" else "Tap to allow", hasMic()) {
                micReq.launch(Manifest.permission.RECORD_AUDIO)
            },
            Item("2. Notifications", if (notifOk) "Granted" else "Tap to allow", notifOk) {
                if (Build.VERSION.SDK_INT >= 33) notifReq.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            Item("3. Accessibility  (touch & control anything)", if (accOk) "On" else "Tap, then turn Lia ON", accOk) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Item("4. Display over other apps  (open apps from background)", if (overlayOk) "Granted" else "Tap to allow", overlayOk) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            },
            Item("5. Modify system settings  (brightness)", if (writeOk) "Granted" else "Tap to allow", writeOk) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
            },
            Item("6. Battery: unrestricted  (keeps Lia alive)", if (battOk) "Done" else "Tap to allow", battOk) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            },
            Item("7. “Hey Lia” listening service", if (svcOn) "ON  ·  tap to turn off" else "OFF  ·  tap to turn on", svcOn) {
                if (svcOn) { Store.setServiceEnabled(false); LiaService.stop(this) }
                else { Store.setServiceEnabled(true); startVoice() }
                renderTab()
            },
            Item("Accessibility greyed out?  Open App info", "Then ⋮ menu → Allow restricted settings", false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
        )
        val sc = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(24))
        sc.addView(col)
        col.addView(tv("Tap each item once. Green means done.", 13f, cSub, false))
        for (item in items) {
            val c = card()
            c.addView(tv(item.title, 15f, cText, true))
            c.addView(tv(item.status, 13f, if (item.good) cGood else cWarn, false))
            c.setOnClickListener { item.act() }
            col.addView(c)
        }
        body.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}
