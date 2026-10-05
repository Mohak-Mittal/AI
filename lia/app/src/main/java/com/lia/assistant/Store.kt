package com.lia.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ApiKey(val id: String, val label: String, val key: String, val enabled: Boolean, val cooldownUntil: Long)

object Store {
    private var secure: SharedPreferences? = null
    private var plain: SharedPreferences? = null
    private val p: SharedPreferences get() = plain!!
    private val s: SharedPreferences get() = secure!!

    @Synchronized
    fun init(ctx: Context) {
        if (plain != null) return
        val app = ctx.applicationContext
        plain = app.getSharedPreferences("lia_cfg", Context.MODE_PRIVATE)
        secure = try {
            val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "lia_keys", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            app.getSharedPreferences("lia_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun keys(): List<ApiKey> {
        val arr = try { JSONArray(s.getString("keys", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ApiKey(o.getString("id"), o.optString("label"), o.getString("key"), o.optBoolean("enabled", true), o.optLong("cd", 0))
        }
    }

    private fun save(list: List<ApiKey>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("key", it.key)
                .put("enabled", it.enabled).put("cd", it.cooldownUntil))
        }
        s.edit().putString("keys", arr.toString()).apply()
    }

    @Synchronized
    fun addKey(label: String, key: String) {
        val cur = keys()
        val name = if (label.isBlank()) "Groq #${cur.size + 1}" else label.trim()
        save(cur + ApiKey(UUID.randomUUID().toString(), name, key.trim(), true, 0L))
    }

    @Synchronized fun deleteKey(id: String) = save(keys().filter { it.id != id })

    @Synchronized
    fun toggleKey(id: String) = save(keys().map { if (it.id == id) it.copy(enabled = !it.enabled, cooldownUntil = 0L) else it })

    @Synchronized
    fun fail(id: String, ms: Long) = save(keys().map { if (it.id == id) it.copy(cooldownUntil = System.currentTimeMillis() + ms) else it })

    @Synchronized
    fun pick(exclude: Set<String>): ApiKey? {
        val now = System.currentTimeMillis()
        val ok = keys().filter { it.enabled && it.cooldownUntil <= now && it.id !in exclude }
        if (ok.isEmpty()) return null
        val i = p.getInt("rot", 0)
        p.edit().putInt("rot", i + 1).apply()
        return ok[i % ok.size]
    }

    fun model(): String = p.getString("model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
    fun setModel(m: String) { p.edit().putString("model", m.trim().ifEmpty { "llama-3.3-70b-versatile" }).apply() }
    fun serviceEnabled(): Boolean = p.getBoolean("svc", true)
    fun setServiceEnabled(v: Boolean) { p.edit().putBoolean("svc", v).apply() }

    @Synchronized
    fun history(): List<JSONObject> {
        val a = try { JSONArray(p.getString("hist", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Synchronized
    fun addHistory(role: String, text: String) {
        val l = (history() + JSONObject().put("role", role).put("content", text)).takeLast(20)
        val a = JSONArray()
        l.forEach { a.put(it) }
        p.edit().putString("hist", a.toString()).apply()
    }
}
