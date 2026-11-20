package com.adriarai.payambin;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * مدیریت گرهٔ Tailscale داخلی (tsnet) برای دسترسی اینترنتی به پیام‌بین.
 *
 * <p>فقط خودِ پیام‌بین به tailnet وصل می‌شود (حالت userspace)؛ هیچ
 * VpnService و هیچ اسلات VPN سیستم اشغال نمی‌شود و بقیهٔ گوشی دست
 * نمی‌خورد. گرهٔ tsnet روی همان پورت وب‌سرور گوش می‌دهد و هر اتصال
 * tailnet را به 127.0.0.1:{@link Prefs#port} (وب‌سرورِ خود برنامه)
 * پروکسی می‌کند؛ بنابراین WebServer.java بدون تغییر می‌ماند و
 * Net.isLocal() هم 100.x را از قبل قبول می‌کند.</p>
 *
 * <p>کلاس Go از طریق reflection صدا زده می‌شود تا اگر AAR ساخته نشده
 * بود، برنامه همچنان کامپایل و اجرا شود و Tailscale فقط «در دسترس
 * نیست» گزارش شود. همهٔ فراخوانی‌ها در try/catch هستند و روی رشتهٔ
 * پس‌زمینه انجام می‌شوند.</p>
 */
public final class TailscaleManager {
    // gomobile: بستهٔ Go ‏tsnetmobile با ‎-javapkg=com.adriarai.payambin.tsnet
    // به کلاس com.adriarai.payambin.tsnet.tsnetmobile.Tsnetmobile تبدیل
    // می‌شود و نام متدها با حرف کوچک می‌آید (start/status/stop/isRunning).
    private static final String GO_CLASS = "com.adriarai.payambin.tsnet.tsnetmobile.Tsnetmobile";
    private static volatile boolean starting = false;

    private TailscaleManager() {}

    /** آیا AAR ساخته شده و دستگاه اندروید ۸ (API 26) یا بالاتر است؟ */
    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            Class.forName(GO_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** لیست اینترفیس‌های شبکه از API اندروید، به‌صورت JSON برای Go.
     * Go روی اندروید نه netlink دارد و نه همیشه به /sys/class/net
     * دسترسی دارد؛ این لیست همان چیزی است که netmon لازم دارد.
     * عین تکنیک خودِ Net.localIps: برای هر اینترفیس try/catch جدا،
     * چون روی بعضی گوشی‌ها یک متد (مثلاً getMTU روی یک اینترفیس
     * خاص) استثنا می‌دهد و نباید کل لیست را خراب کند. */
    private static String collectInterfacesJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray list = new JSONArray();
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis != null) {
                while (nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    try {
                        JSONObject o = new JSONObject();
                        String name = ni.getName() == null ? "" : ni.getName();
                        o.put("name", name);
                        try { o.put("index", ni.getIndex()); } catch (Throwable ignored) { o.put("index", 0); }
                        try { o.put("mtu", ni.getMTU()); } catch (Throwable ignored) { o.put("mtu", 0); }
                        byte[] hw = null;
                        try { hw = ni.getHardwareAddress(); } catch (Throwable ignored) {}
                        if (hw != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < hw.length; i++) {
                                if (i > 0) sb.append(':');
                                sb.append(String.format("%02x", hw[i] & 0xff));
                            }
                            o.put("hw", sb.toString());
                        } else {
                            o.put("hw", "");
                        }
                        boolean up = false;
                        boolean loopback = false;
                        try { up = ni.isUp(); } catch (Throwable ignored) {}
                        try { loopback = ni.isLoopback(); } catch (Throwable ignored) {}
                        o.put("up", up);
                        o.put("loopback", loopback);
                        // طول prefix را از getInterfaceAddresses می‌گیریم،
                        // ولی اگر نشد، آدرس را با /32 یا /128 می‌گذاریم.
                        java.util.Map<String, Integer> bitsByIp = new java.util.HashMap<>();
                        try {
                            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                                if (ia == null || ia.getAddress() == null) continue;
                                bitsByIp.put(ia.getAddress().getHostAddress(), (int) ia.getNetworkPrefixLength());
                            }
                        } catch (Throwable ignored) {}
                        JSONArray addrs = new JSONArray();
                        for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                            if (a == null) continue;
                            String ip = a.getHostAddress();
                            if (ip == null) continue;
                            int pct = ip.indexOf('%');
                            if (pct >= 0) ip = ip.substring(0, pct); // حذف zone در IPv6
                            JSONObject ja = new JSONObject();
                            ja.put("ip", ip);
                            Integer bits = bitsByIp.get(ip);
                            if (bits == null) bits = (a instanceof java.net.Inet4Address) ? 32 : 128;
                            ja.put("bits", bits.intValue());
                            addrs.put(ja);
                        }
                        o.put("addrs", addrs);
                        list.put(o);
                    } catch (Throwable ignored) {
                        // این اینترفیس را رد می‌کنیم، بقیه می‌مانند.
                    }
                }
            }
            root.put("interfaces", list);
            return root.toString();
        } catch (Throwable e) {
            return "{\"interfaces\":[]}";
        }
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        return Class.forName(GO_CLASS).getMethod(name, params);
    }

    private static String callString(String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
            for (int i = 0; i < types.length; i++) if (types[i] == Long.class) types[i] = long.class;
            Method m = method(name, types);
            Object r = m.invoke(null, args);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable e) {
            return "error: " + String.valueOf(e.getMessage());
        }
    }

    /** شروع گرهٔ tsnet در پس‌زمینه؛ اگر غیرفعال یا در دسترس نباشد هیچ کاری نمی‌کند. */
    public static void start(final Context ctx) {
        if (!Prefs.tsnetEnabled(ctx) || !isAvailable() || starting) return;
        starting = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String hostname = Prefs.tsnetHostname(app);
                String authKey = Prefs.tsnetAuthKey(app);
                String stateDir = app.getFilesDir().getAbsolutePath() + "/tsnet";
                // لیست اینترفیس‌ها از API اندروید (بدون netlink): Go روی
                // اندروید نمی‌تواند net.Interfaces() را صدا بزند و حتی
                // خواندن /sys/class/net هم روی بعضی گوشی‌ها ممنوع است.
                String ifErr = callString("setInterfacesJSON", collectInterfacesJson());
                if (ifErr != null && !ifErr.isEmpty()) Diag.log("لیست کارت شبکه به Go نرسید: " + ifErr);
                callString("start", hostname, authKey, stateDir, Long.valueOf(Prefs.port(app)));
                Diag.log("Tailscale شروع شد — " + statusText(app));
            } catch (Throwable e) {
                Diag.log("شروع Tailscale ممکن نشد: " + e.getMessage());
            } finally {
                starting = false;
            }
        }, "payambin-tsnet").start();
    }

    /** توقف گرهٔ tsnet. */
    public static void stop(Context ctx) {
        if (!isAvailable()) return;
        try {
            callString("stop");
        } catch (Throwable ignored) {
        }
    }

    /** وضعیت خام JSON از Go؛ اگر در دسترس نباشد JSON حالت stopped برمی‌گردد. */
    public static String statusJson(Context ctx) {
        if (!isAvailable()) return "{\"state\":\"unavailable\"}";
        try {
            Method m = method("status");
            Object r = m.invoke(null);
            return r == null ? "{\"state\":\"error\"}" : String.valueOf(r);
        } catch (Throwable e) {
            return "{\"state\":\"error\",\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    /** آیا گره روی tailnet بالا و در دسترس است؟ */
    public static boolean isRunning(Context ctx) {
        if (!isAvailable()) return false;
        try {
            Method m = method("isRunning");
            Object r = m.invoke(null);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** آدرس اینترنتی پیام‌بین روی tailnet، مثل http://payambin-tsnet:9191؛ اگر بالا نیست null. */
    public static String address(Context ctx) {
        try {
            JSONObject s = new JSONObject(statusJson(ctx));
            if (!"running".equals(s.optString("state"))) return null;
            String a = s.optString("address", "");
            return a.isEmpty() ? null : a;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** یک خط وضعیت فارسی برای نمایش در تنظیمات. */
    public static String statusText(Context ctx) {
        if (!Prefs.tsnetEnabled(ctx)) return "خاموش است";
        if (!isAvailable()) {
            return Build.VERSION.SDK_INT < 26
                    ? "روی این گوشی پشتیبانی نمی‌شود (نیاز به اندروید ۸+)"
                    : "ماژول Tailscale ساخته نشده است";
        }
        try {
            JSONObject s = new JSONObject(statusJson(ctx));
            String state = s.optString("state", "?");
            switch (state) {
                case "running":
                    return "● وصل است — " + s.optString("address", "")
                            + (s.optString("ip", "").isEmpty() ? "" : "  (" + s.optString("ip") + ")");
                case "needs-login":
                    return "● نیاز به ورود به Tailscale دارد — دکمهٔ «باز کردن لینک ورود» را بزنید";
                case "starting":
                    return "… در حال اتصال به Tailscale";
                case "error":
                    return "● خطا: " + s.optString("error", "?");
                case "stopped":
                    return "متوقف است";
                default:
                    return "وضعیت: " + state;
            }
        } catch (Throwable e) {
            return "وضعیت نامشخص";
        }
    }

    /** لینک ورود تعاملی، وقتی گره نیاز به ورود دارد؛ در غیر این صورت "". */
    public static String loginUrl(Context ctx) {
        try {
            return new JSONObject(statusJson(ctx)).optString("loginUrl", "");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
