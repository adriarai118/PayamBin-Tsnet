package com.adriarai.payambin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** روشن شدن خودکار سرور بعد از ری‌استارت گوشی یا به‌روزرسانی برنامه */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.autoStart(c) && Prefs.wantRunning(c)) {
            try {
                ServerService.start(c);
            } catch (Exception ignored) {
            }
        }
        // نگهبان سرور را هم مسلح کن تا بعد از بوت بررسی دوره‌ای ادامه داشته باشد
        try {
            Watchdog.schedule(c);
        } catch (Exception ignored) {
        }
    }
}
