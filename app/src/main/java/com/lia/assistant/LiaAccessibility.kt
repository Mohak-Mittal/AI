package com.lia.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LiaAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: LiaAccessibility? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun global(a: String): Boolean = when (a) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "enter" -> pressEnter()
        else -> false
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 40) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "The screen can't be read right now."
        val sb = StringBuilder()
        var cnt = 0
        walk(root, 0) { n ->
            if (cnt < 100) {
                val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (t.isNotBlank()) {
                    sb.append("- ").append(t.take(60))
                    if (n.isClickable) sb.append(" [tappable]")
                    if (n.isEditable) sb.append(" [input]")
                    sb.append('\n')
                    cnt++
                }
            }
        }
        return "App: ${root.packageName}\n$sb"
    }

    fun clickText(q: String, minTop: Int = 0): Boolean {
        val root = rootInActiveWindow ?: return false
        val ql = q.lowercase().trim()
        if (ql.isEmpty()) return false
        var exact: AccessibilityNodeInfo? = null
        var part: AccessibilityNodeInfo? = null
        walk(root, 0) { n ->
            val t = (n.text?.toString() ?: "").lowercase()
            val d = (n.contentDescription?.toString() ?: "").lowercase()
            if (t.isNotEmpty() || d.isNotEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.centerY() >= minTop) {
                    if (t == ql || d == ql) { if (exact == null) exact = n }
                    else if (t.contains(ql) || d.contains(ql)) { if (part == null) part = n }
                }
            }
        }
        val target = exact ?: part ?: return false
        return clickNode(target)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        var i = 0
        while (p != null && i < 8) {
            if (p.isClickable && p.isEnabled) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p = p.parent
            i++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        tap(r.exactCenterX(), r.exactCenterY())
        return true
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        dispatchGesture(g, null, null)
        Thread.sleep(dur + 250)
    }

    fun tap(x: Float, y: Float) = gesture(x, y, x, y, 60)

    /** direction = the way the FINGER moves. */
    fun swipe(dir: String) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        when (dir) {
            "up" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.25f, 350)
            "down" -> gesture(w / 2, h * 0.25f, w / 2, h * 0.75f, 350)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
        }
    }

    private fun inputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var node: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) walk(root, 0) { n -> if (node == null && n.isEditable) node = n }
        return node
    }

    fun typeText(t: String): Boolean {
        val n = inputNode() ?: return false
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + t)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = inputNode() ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Opens quick settings and taps the first tile whose label matches. */
    fun toggleTile(labels: List<String>): Boolean {
        global("quick_settings")
        Thread.sleep(1300)
        val top = 150
        var done = false
        for (attempt in 0..2) {
            for (l in labels) {
                if (clickText(l, top)) { done = true; break }
            }
            if (done) break
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            gesture(w / 2, h * 0.12f, w / 2, h * 0.65f, 400)
            Thread.sleep(700)
        }
        Thread.sleep(600)
        global("back")
        return done
    }
}
