package com.adriarai.payambin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * نگهبان سرور: اگر سرور به هر دلیل خاموش شد (کرش، کشته شدن توسط سیستم)،
 * خودکار دوباره روشنش می‌کند.
 *
 * سازوکار: یک آلارم دقیق (exact alarm) هر ۱۵ دقیقه WatchdogReceiver را
 * بیدار می‌کند؛ آلارم دقیق از معافیت‌های شروع سرویس پیش‌زمینه در اندروید
 * ۱۲+ است، پس روشن کردن سرور از پس‌زمینه مجاز است. اگر اندروید باز هم
 * اجازه نداد، به‌جای آن یک اعلان می‌آید که با یک لمس سرور را روشن می‌کند.
 * زنجیره‌ی آلارم فقط وقتی ادامه دارد که سرور باید روشن باشد
 * (Prefs.wantRunning)؛ وقتی کاربر سرور را خاموش کند، زنجیره متوقف می‌شود.
 */
public final class Watchdog {
    private Watchdog() {}

    public static final String ACTION_CHECK = "com.adriarai.payambin.WATCHDOG_CHECK";
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    /** بررسی دوره‌ای (هر ۱۵ دقیقه) */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        setAlarm(am, System.currentTimeMillis() + INTERVAL_MS, pi(c));
    }

    /** روشن کردن سریع (چند ثانیه بعد) — برای بعد از کرش */
    public static void scheduleSoon(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        setAlarm(am, System.currentTimeMillis() + 3000, pi(c));
    }

    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            // مجوز آلارم دقیق داده نشده؛ با آلارم معمولی ادامه می‌دهیم
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) try { am.cancel(pi(c)); } catch (Exception ignored) {}
    }
}
