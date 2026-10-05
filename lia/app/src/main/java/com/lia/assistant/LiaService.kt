package com.lia.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

class LiaService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val STOP = "com.lia.assistant.STOP"
        fun start(c: Context) {
            try { ContextCompat.startForegroundService(c, Intent(c, LiaService::class.java)) } catch (e: Throwable) { }
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, LiaService::class.java)) } catch (e: Throwable) { }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var busy = false
    private var running = true
    private var awaiting = false
    private var followUntil = 0L
    private val wake = Regex("\\b(hey|hay|hi|ok|okay|hello)\\s+(lia|leah|lea|liya|leeya|lya|lee a|li a)\\b")

    private val listenRun = Runnable {
        if (!running || speaking || busy) return@Runnable
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return@Runnable
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listen(15000)
            return@Runnable
        }
        try {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(this)
            rec = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            r.startListening(i)
        } catch (e: Throwable) {
            listen(2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lia", "Lia assistant", NotificationManager.IMPORTANCE_LOW))
        goForeground()
        tts = TextToSpeech(this, this)
        listen(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Store.setServiceEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        try {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, LiaService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val n = Notification.Builder(this, "lia")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Lia is listening")
                .setContentText("Say “Hey Lia”")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Throwable) {
            stopSelf()
        }
    }

    private fun listen(delay: Long) {
        h.removeCallbacks(listenRun)
        h.postDelayed(listenRun, delay)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale("en", "IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishedSpeaking() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finishedSpeaking() }
        })
        ttsReady = true
    }

    private fun finishedSpeaking() {
        h.post {
            speaking = false
            followUntil = System.currentTimeMillis() + 7000
            listen(300)
        }
    }

    private fun say(t: String) {
        val clean = t.replace(Regex("[*_`#]"), "").take(500)
        if (!ttsReady) { listen(300); return }
        speaking = true
        try { rec?.cancel() } catch (e: Throwable) { }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "lia")
    }

    private fun handle(cmd: String) {
        busy = true
        Agent.ask(this, cmd) { reply ->
            h.post {
                busy = false
                say(reply)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        val now = System.currentTimeMillis()
        var woke = false
        var cmd = ""
        for (t in list) {
            val lt = t.lowercase(Locale.ROOT)
            val m = wake.find(lt)
            if (m != null) {
                woke = true
                cmd = lt.substring(m.range.last + 1).trim(' ', ',', '.')
                break
            }
        }
        if (woke) {
            if (cmd.isBlank()) {
                awaiting = true
                say("Yes?")
            } else {
                awaiting = false
                handle(cmd)
            }
            return
        }
        if ((awaiting || now < followUntil) && list.isNotEmpty()) {
            awaiting = false
            followUntil = 0L
            handle(list[0])
            return
        }
        listen(100)
    }

    override fun onError(error: Int) {
        if (speaking || busy) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { awaiting = false; listen(100) }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                try { rec?.destroy() } catch (e: Throwable) { }
                rec = null
                listen(1000)
            }
            else -> listen(1500)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Throwable) { }
        try { tts?.shutdown() } catch (e: Throwable) { }
        super.onDestroy()
    }
}
