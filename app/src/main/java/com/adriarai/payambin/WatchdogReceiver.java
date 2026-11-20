package com.adriarai.payambin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** گیرنده‌ی آلارم نگهبان سرور: اگر سرور باید روشن باشد ولی نیست، روشنش می‌کند */
public class WatchdogReceiver extends BroadcastReceiver {
    private static final String CH = "watchdog";
    private static final int NOTIF_ID = 9002;

    @Override
    public void onReceive(Context c, Intent intent) {
        // اگر کاربر سرور را خاموش کرده، زنجیره‌ی بررسی متوقف می‌شود
        if (!Prefs.wantRunning(c)) return;
        // بررسی بعدی ۱۵ دقیقه‌ی دیگر
        Watchdog.schedule(c);
        if (ServerService.running) return;
        try {
            ServerService.start(c);
            Diag.log("نگهبان: سرور خاموش بود، دوباره روشن شد");
        } catch (Exception e) {
            // اندروید ۱۲+ گاهی شروع سرویس از پس‌زمینه را نمی‌پذیرد؛
            // با یک اعلان به کاربر خبر می‌دهیم تا با یک لمس روشنش کند
            notifyDown(c);
        }
    }

    private void notifyDown(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "نگهبان سرور",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(c, ServerService.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent tap = PendingIntent.getForegroundService(c, 0, i, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("سرور پیام‌بین خاموش شده")
                    .setContentText("برای روشن کردن دوباره، بزنید")
                    .setContentIntent(tap)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {}
    }
}
