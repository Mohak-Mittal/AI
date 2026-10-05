package com.lia.assistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Html
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NEED_ACC = "Accessibility is off. Tell the user to turn on Lia in Settings > Accessibility (Setup tab)."

object Tools {

    private fun tool(name: String, desc: String, vararg props: Array<String>, req: String = ""): JSONObject {
        val p = JSONObject()
        for (x in props) p.put(x[0], JSONObject().put("type", x[1]).put("description", x[2]))
        val params = JSONObject().put("type", "object").put("properties", p)
        if (req.isNotBlank()) params.put("required", JSONArray(req.split(",")))
        return JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    val TOOLS: JSONArray by lazy {
        val l = listOf(
            tool("open_app", "Open an installed app by name.", arrayOf("name", "string", "App name, e.g. YouTube"), req = "name"),
            tool("open_url", "Open a web address in the browser.", arrayOf("url", "string", "Full URL"), req = "url"),
            tool("web_search", "Open a Google search in the browser so the user can see results.", arrayOf("query", "string", "Search text"), req = "query"),
            tool("web_lookup", "Search the web and get text snippets back so you can answer factual or current questions (weather, news, scores).", arrayOf("query", "string", "Search text"), req = "query"),
            tool("volume", "Change volume.", arrayOf("action", "string", "up, down, mute, unmute or set"), arrayOf("level", "integer", "0-100, for set"), arrayOf("steps", "integer", "steps for up/down, default 3"), arrayOf("stream", "string", "media (default), ring or alarm"), req = "action"),
            tool("brightness", "Set or change screen brightness.", arrayOf("percent", "integer", "Absolute 0-100"), arrayOf("change", "integer", "Relative change, e.g. -20 or 20")),
            tool("flashlight", "Turn the flashlight on or off.", arrayOf("on", "boolean", "true for on"), req = "on"),
            tool("toggle_system", "Turn a phone feature on/off through quick settings.", arrayOf("feature", "string", "wifi, bluetooth, airplane, mobile_data, hotspot, auto_rotate, dnd, location, nfc, battery_saver"), arrayOf("state", "string", "on, off or toggle"), req = "feature"),
            tool("open_settings", "Open a settings screen.", arrayOf("page", "string", "wifi, bluetooth, airplane, internet, mobile_data, hotspot, display, sound, volume, battery, apps, location, nfc, accessibility, storage, date, security, vpn, language, about, developer, settings"), req = "page"),
            tool("device_status", "Battery, charging, Wi-Fi, Bluetooth, airplane mode, connectivity, brightness, volume, time, device info."),
            tool("screen_read", "List the text visible on screen (needed before tapping things in apps)."),
            tool("tap_text", "Tap a button/label on screen by its text.", arrayOf("text", "string", "Visible text or description"), req = "text"),
            tool("tap_xy", "Tap screen coordinates in pixels.", arrayOf("x", "number", "x"), arrayOf("y", "number", "y"), req = "x,y"),
            tool("scroll", "Scroll the current screen to see more content.", arrayOf("direction", "string", "down (see content further down) or up"), req = "direction"),
            tool("swipe", "Swipe a finger across the screen.", arrayOf("direction", "string", "Direction the finger moves: up, down, left, right"), req = "direction"),
            tool("type_text", "Type text into the focused text field.", arrayOf("text", "string", "Text to type"), req = "text"),
            tool("system_button", "Press a system button/action.", arrayOf("action", "string", "back, home, recents, notifications, quick_settings, lock_screen, screenshot, power_dialog, enter"), req = "action"),
            tool("set_alarm", "Set an alarm/reminder at a clock time.", arrayOf("hour", "integer", "0-23"), arrayOf("minute", "integer", "0-59"), arrayOf("message", "string", "Label"), req = "hour,minute"),
            tool("set_timer", "Start a countdown timer.", arrayOf("seconds", "integer", "Duration in seconds"), arrayOf("message", "string", "Label"), req = "seconds"),
            tool("media", "Control music/video playback.", arrayOf("action", "string", "play_pause, next, previous, stop"), req = "action"),
            tool("dial", "Open the phone dialer with a number.", arrayOf("number", "string", "Phone number"), req = "number"),
            tool("vibrate", "Vibrate the phone.", arrayOf("ms", "integer", "Milliseconds")),
            tool("wait", "Wait a moment (e.g. for an app to load).", arrayOf("seconds", "number", "1-6")),
            tool("file_write", "Write a text file in Lia's folder.", arrayOf("path", "string", "Relative path"), arrayOf("content", "string", "Text"), arrayOf("append", "boolean", "Append instead of overwrite"), req = "path,content"),
            tool("file_read", "Read a text file from Lia's folder.", arrayOf("path", "string", "Relative path"), req = "path"),
            tool("file_list", "List files in Lia's folder.", arrayOf("path", "string", "Relative folder, empty for root")),
            tool("file_mkdir", "Create a folder in Lia's folder.", arrayOf("path", "string", "Relative path"), req = "path"),
            tool("file_delete", "Delete a file or folder in Lia's folder. Only after the user confirmed.", arrayOf("path", "string", "Relative path"), arrayOf("confirmed", "boolean", "true only if the user confirmed"), req = "path,confirmed")
        )
        JSONArray(l)
    }

    fun launch(c: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = LiaAccessibility.instance ?: c
        return try { ctx.startActivity(i); true } catch (e: Throwable) { false }
    }

    private fun acc(c: Context, f: (LiaAccessibility) -> String): String {
        val s = LiaAccessibility.instance
        if (s == null) {
            launch(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return NEED_ACC
        }
        return f(s)
    }

    fun run(c: Context, name: String, a: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(c, a.optString("name"))
            "open_url" -> {
                var u = a.optString("url").trim()
                if (!u.contains("://")) u = "https://$u"
                if (launch(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)))) "Opened $u" else "Couldn't open that link."
            }
            "web_search" -> {
                val q = a.optString("query")
                if (launch(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))) "Showing search results for $q" else "Couldn't open the browser."
            }
            "web_lookup" -> webLookup(c, a.optString("query"))
            "volume" -> volume(c, a)
            "brightness" -> brightness(c, a)
            "flashlight" -> flashlight(c, a.optBoolean("on", true))
            "toggle_system" -> toggleSystem(c, a.optString("feature").lowercase(), a.optString("state", "toggle").lowercase())
            "open_settings" -> openSettings(c, a.optString("page"))
            "device_status" -> status(c)
            "screen_read" -> acc(c) { it.readScreen() }
            "tap_text" -> acc(c) { if (it.clickText(a.optString("text"))) "Tapped it." else "I couldn't find that on the screen." }
            "tap_xy" -> acc(c) { it.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat()); "Tapped." }
            "scroll" -> acc(c) { it.swipe(if (a.optString("direction") == "up") "down" else "up"); "Scrolled." }
            "swipe" -> acc(c) { it.swipe(a.optString("direction")); "Swiped." }
            "type_text" -> acc(c) { if (it.typeText(a.optString("text"))) "Typed." else "No text field is focused." }
            "system_button" -> acc(c) { if (it.global(a.optString("action"))) "Done." else "That action isn't supported on this phone." }
            "set_alarm" -> {
                val h = a.optInt("hour", 0).coerceIn(0, 23)
                val m = a.optInt("minute", 0).coerceIn(0, 59)
                val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("message", "Lia reminder")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (launch(c, i)) "Alarm set for %d:%02d.".format(h, m) else "No clock app could set the alarm."
            }
            "set_timer" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60).coerceAtLeast(1))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("message", "Lia timer")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (launch(c, i)) "Timer started." else "No clock app could start the timer."
            }
            "media" -> media(c, a.optString("action"))
            "dial" -> if (launch(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.optString("number")))))) "Dialer opened." else "Couldn't open the dialer."
            "vibrate" -> {
                @Suppress("DEPRECATION")
                val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(a.optLong("ms", 400).coerceIn(50L, 3000L), VibrationEffect.DEFAULT_AMPLITUDE))
                "Vibrated."
            }
            "wait" -> {
                Thread.sleep((a.optDouble("seconds", 1.0).coerceIn(0.2, 6.0) * 1000).toLong())
                "Waited."
            }
            "file_write", "file_read", "file_list", "file_mkdir", "file_delete" -> files(c, name, a)
            else -> "Unknown tool: $name"
        }
    } catch (e: Throwable) {
        "Failed: " + (e.message ?: e.javaClass.simpleName)
    }

    // ---------- apps & web ----------

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun openApp(c: Context, name: String): String {
        val pm = c.packageManager
        val q = norm(name)
        if (q.isEmpty()) return "Which app?"
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) }
            ?: apps.firstOrNull { val l = norm(it.loadLabel(pm).toString()); l.length > 2 && q.contains(l) }
            ?: return "I couldn't find an app called $name."
        val pkg = best.activityInfo.packageName
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "That app can't be opened."
        return if (launch(c, i)) {
            Thread.sleep(1500)
            "Opened ${best.loadLabel(pm)}."
        } else "Android blocked opening the app. Grant 'Display over other apps' in the Setup tab."
    }

    private fun webLookup(c: Context, q: String): String {
        if (q.isBlank()) return "No query given."
        try {
            val conn = URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val re = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val out = re.findAll(html)
                .map { Html.fromHtml(it.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                .filter { it.isNotEmpty() }.take(5).toList()
            if (out.isNotEmpty()) return out.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        } catch (e: Throwable) {
        }
        launch(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return "I couldn't read the results directly, so I opened the search in the browser."
    }

    // ---------- device controls ----------

    private fun volume(c: Context, a: JSONObject): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = when (a.optString("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val steps = a.optInt("steps", 3).coerceIn(1, 15)
        when (a.optString("action")) {
            "up" -> for (k in 0 until steps) am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, 0)
            "down" -> for (k in 0 until steps) am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, 0)
            "mute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
            "set" -> am.setStreamVolume(st, a.optInt("level", 50).coerceIn(0, 100) * am.getStreamMaxVolume(st) / 100, 0)
            else -> return "Unknown volume action."
        }
        am.adjustStreamVolume(st, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        return "Volume is now ${am.getStreamVolume(st) * 100 / am.getStreamMaxVolume(st)}%."
    }

    private fun brightness(c: Context, a: JSONObject): String {
        if (!Settings.System.canWrite(c)) {
            launch(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
            return "I need the 'Modify system settings' permission. I opened its page; turn it on for Lia, then ask again."
        }
        val cr = c.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val target = if (a.has("percent")) a.optInt("percent") else cur + a.optInt("change", 0)
        val pct = target.coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100)
        return "Brightness set to $pct%."
    }

    private fun flashlight(c: Context, on: Boolean): String {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun wifiOn(c: Context): Boolean? = try {
        (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Throwable) { null }

    private fun btOn(c: Context): Boolean? = try {
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
    } catch (e: Throwable) { null }

    private fun airplaneOn(c: Context): Boolean = Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun stateOf(c: Context, f: String): Boolean? = when (f) {
        "wifi" -> wifiOn(c)
        "bluetooth" -> btOn(c)
        "airplane" -> airplaneOn(c)
        else -> null
    }

    private fun toggleSystem(c: Context, f: String, want: String): String {
        val labels: List<String> = when (f) {
            "wifi" -> listOf("wi-fi", "wifi", "internet")
            "bluetooth" -> listOf("bluetooth")
            "airplane" -> listOf("airplane", "aeroplane", "flight mode")
            "mobile_data" -> listOf("mobile data", "internet")
            "hotspot" -> listOf("hotspot")
            "auto_rotate" -> listOf("auto-rotate", "auto rotate", "rotate")
            "dnd" -> listOf("do not disturb", "dnd")
            "location" -> listOf("location")
            "nfc" -> listOf("nfc")
            "battery_saver" -> listOf("battery saver", "power saving")
            else -> return "I can't toggle '$f'."
        }
        val before = stateOf(c, f)
        if (before != null && want != "toggle" && before == (want == "on")) return "$f is already $want."
        return acc(c) { s ->
            val ok = s.toggleTile(labels)
            Thread.sleep(1200)
            val after = stateOf(c, f)
            if (after != null) {
                if (after != before) "$f is now ${if (after) "on" else "off"}."
                else "I tapped the $f tile but it didn't change. The phone may need a manual confirmation."
            } else if (ok) "I tapped the $f tile." else "I couldn't find the $f tile in quick settings."
        }
    }

    private fun openSettings(c: Context, page: String): String {
        val p = page.lowercase().trim()
        val action: String = when (p) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "vpn" -> "android.settings.VPN_SETTINGS"
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        if (launch(c, Intent(action))) return "Opened $p settings."
        return if (launch(c, Intent(Settings.ACTION_WIRELESS_SETTINGS))) "Opened network settings." else "Couldn't open that settings page."
    }

    private fun status(c: Context): String {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "online via Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "online via mobile data"
            else -> "online"
        }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val br = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0) * 100 / 255
        val time = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date())
        return "Battery ${if (lvl >= 0) lvl * 100 / scale else -1}% (${if (charging) "charging" else "not charging"}); " +
            "Wi-Fi ${wifiOn(c)}; Bluetooth ${btOn(c)}; airplane mode ${airplaneOn(c)}; $net; " +
            "media volume $vol%; brightness $br%; $time; device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}."
    }

    private fun media(c: Context, action: String): String {
        val code = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Unknown media action."
        }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Done."
    }

    // ---------- files ----------

    private fun base(c: Context): File {
        val f = File(c.getExternalFilesDir(null) ?: c.filesDir, "Lia")
        f.mkdirs()
        return f
    }

    private fun resolve(c: Context, p: String): File? {
        val b = base(c).canonicalFile
        val f = File(b, p.trim().trimStart('/')).canonicalFile
        return if (f.path == b.path || f.path.startsWith(b.path + File.separator)) f else null
    }

    private fun files(c: Context, name: String, a: JSONObject): String {
        val f = resolve(c, a.optString("path", "")) ?: return "That path is outside Lia's folder."
        return when (name) {
            "file_write" -> {
                f.parentFile?.mkdirs()
                if (a.optBoolean("append", false)) f.appendText(a.optString("content")) else f.writeText(a.optString("content"))
                "Saved ${f.name}."
            }
            "file_read" -> if (f.isFile) f.readText().take(4000) else "No such file."
            "file_list" -> {
                val l = f.listFiles()
                if (l == null || l.isEmpty()) "Empty." else l.sortedBy { it.name }.joinToString("\n") { (if (it.isDirectory) "[folder] " else "") + it.name }
            }
            "file_mkdir" -> if (f.mkdirs() || f.isDirectory) "Folder ready." else "Couldn't create the folder."
            else -> {
                if (!a.optBoolean("confirmed", false)) "Ask the user to confirm first."
                else if (f == base(c).canonicalFile) "I won't delete the root folder."
                else if (f.deleteRecursively()) "Deleted." else "Couldn't delete it."
            }
        }
    }
}
