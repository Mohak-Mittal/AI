package com.lia.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            Store.init(context)
            if (Store.serviceEnabled()) LiaService.start(context)
        } catch (e: Throwable) {
        }
    }
}
