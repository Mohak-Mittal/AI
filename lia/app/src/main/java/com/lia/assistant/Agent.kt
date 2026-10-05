package com.lia.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object Agent {
    val listeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    private val exec = Executors.newSingleThreadExecutor()

    private fun emit(role: String, text: String) {
        for (l in listeners) {
            try { l(role, text) } catch (e: Throwable) { }
        }
    }

    fun ask(ctx: Context, text: String, cb: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        exec.execute {
            Store.init(app)
            Store.addHistory("user", text)
            emit("user", text)
            val reply = try { think(app) } catch (e: Throwable) { "Sorry, " + (e.message ?: "something went wrong") }
            Store.addHistory("assistant", reply)
            emit("assistant", reply)
            emit("status", "Ready")
            cb?.invoke(reply)
        }
    }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return "You are Lia, a friendly voice assistant living on the user's Android phone. Current date and time: $now. " +
            "Your replies are spoken aloud, so use one or two short plain sentences, no markdown, no emojis. " +
            "Use the tools to act on the phone. Never say something worked unless the tool result confirms it; if a tool says a permission or setting is needed, tell the user briefly what to enable. " +
            "To use another app: open_app, wait, screen_read, then tap_text / type_text / system_button(enter) as needed. " +
            "Use web_lookup for facts, news, weather and scores; use web_search only to show results in the browser. " +
            "For reminders at a clock time use set_alarm (24-hour); for 'in N minutes' use set_timer. " +
            "Always ask the user to confirm before deleting files."
    }

    private fun txt(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    private fun think(app: Context): String {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system()))
        Store.history().dropWhile { it.optString("role") != "user" }.forEach {
            msgs.put(JSONObject().put("role", it.optString("role")).put("content", it.optString("content")))
        }
        for (round in 0 until 6) {
            emit("status", "Thinking…")
            val msg = chat(msgs).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val t = txt(msg, "content").trim()
                return if (t.isEmpty()) "Done." else t
            }
            msgs.put(JSONObject().put("role", "assistant").put("content", txt(msg, "content")).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.getString("name")
                val raw = fn.optString("arguments", "{}")
                val args = try { JSONObject(if (raw.isBlank()) "{}" else raw) } catch (e: Exception) { JSONObject() }
                emit("status", "⚙ $name")
                val out = Tools.run(app, name, args)
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out.take(1500)))
            }
        }
        return "That needed too many steps, so I stopped. Please try a simpler request."
    }

    private fun chat(msgs: JSONArray): JSONObject {
        if (Store.keys().none { it.enabled }) throw RuntimeException("no API key is enabled. Add one in the Keys tab.")
        val body = JSONObject()
            .put("model", Store.model()).put("messages", msgs).put("tools", Tools.TOOLS)
            .put("tool_choice", "auto").put("temperature", 0.3).put("max_tokens", 700).toString()
        val tried = HashSet<String>()
        var err = "all API keys are resting after rate limits. Try again in a minute."
        while (true) {
            val k = Store.pick(tried) ?: break
            tried.add(k.id)
            val res = try { post(k.key, body) } catch (e: IOException) { throw RuntimeException("I can't reach the internet.") }
            val code = res.first
            val text = res.second
            if (code in 200..299) return JSONObject(text)
            if (code == 429) { Store.fail(k.id, 60_000L); err = "rate limit reached on ${k.label}." }
            else if (code == 401 || code == 403) { Store.fail(k.id, 1_800_000L); err = "key ${k.label} was rejected (invalid or revoked)." }
            else if (code >= 500) { Store.fail(k.id, 20_000L); err = "Groq is having trouble right now." }
            else err = "request error $code: " + text.take(160)
        }
        throw RuntimeException(err)
    }

    private fun post(key: String, body: String): Pair<Int, String> {
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val s = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, s)
    }
}
