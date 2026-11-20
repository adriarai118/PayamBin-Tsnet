package com.adriarai.payambin;

import android.Manifest;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** لوکیشن لحظه‌ای گوشی */
public final class Loc {
    private Loc() {}

    public static JSONObject now(Context ctx) {
        boolean fine = SmsRepo.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = SmsRepo.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) {
            return J.obj("ok", false, "error",
                    "مجوز لوکیشن داده نشده؛ در برنامه ← مجوزها ← «لوکیشن گوشی» را فعال کنید");
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return J.obj("ok", false, "error", "سرویس لوکیشن در دسترس نیست");
        Location best = bestLastKnown(lm, fine);
        // اگر آخرین موقعیت تازه است (کمتر از ۲ دقیقه)، همان کافی است
        if (best != null && System.currentTimeMillis() - best.getTime() < 120_000) {
            return toJson(best, true);
        }
        // وگرنه یک موقعیت تازه می‌گیریم؛ حداکثر ۱۰ ثانیه صبر می‌کنیم
        final AtomicReference<Location> found = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        LocationListener ll = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (l == null) return;
                Location prev = found.get();
                if (prev == null || l.getAccuracy() < prev.getAccuracy()) found.set(l);
                if (l.getAccuracy() < 50) latch.countDown();
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) {}

            @Override
            public void onProviderEnabled(String p) {}

            @Override
            public void onProviderDisabled(String p) {}
        };
        try {
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, ll, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, ll, Looper.getMainLooper());
            }
            latch.await(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try { lm.removeUpdates(ll); } catch (Exception ignored) {}
        }
        Location f = found.get();
        if (f != null) return toJson(f, false);
        if (best != null) return toJson(best, true);
        return J.obj("ok", false, "error",
                "موقعیتی پیدا نشد؛ GPS گوشی را روشن کنید و دوباره امتحان کنید");
    }

    private static Location bestLastKnown(LocationManager lm, boolean fine) {
        Location best = null;
        try {
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                best = newer(best, lm.getLastKnownLocation(LocationManager.GPS_PROVIDER));
            }
        } catch (SecurityException ignored) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                best = newer(best, lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            }
        } catch (SecurityException ignored) {}
        return best;
    }

    private static Location newer(Location a, Location b) {
        if (b == null) return a;
        if (a == null) return b;
        return b.getTime() > a.getTime() ? b : a;
    }

    private static JSONObject toJson(Location l, boolean cached) {
        return J.obj("ok", true,
                "lat", l.getLatitude(), "lon", l.getLongitude(),
                "acc", Math.round(l.getAccuracy()),
                "provider", l.getProvider() == null ? "" : l.getProvider(),
                "cached", cached,
                "age", Math.max(0, (System.currentTimeMillis() - l.getTime()) / 1000),
                "at", l.getTime());
    }
}
