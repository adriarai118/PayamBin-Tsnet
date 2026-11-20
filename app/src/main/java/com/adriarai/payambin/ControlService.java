package com.adriarai.payambin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

/** کنترل گوشی از کامپیوتر: لمس، کشیدن، اسکرول، دکمه‌های سیستم و تایپ */
public class ControlService extends AccessibilityService {
    public static volatile ControlService instance;

    public static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(c, ControlService.class).flattenToString();
        for (String part : s.split(":")) {
            if (part.equalsIgnoreCase(me)) return true;
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && cn.getPackageName().equals(c.getPackageName())) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Diag.init(this);
        Diag.log("کنترل از کامپیوتر فعال شد");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            int t = event.getEventType();
            if (t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence p = event.getPackageName();
            if (p == null || !isChatPkg(p.toString())) return;
            long now = System.currentTimeMillis();
            if (now - lastAutoAt < 2000) return;
            lastAutoAt = now;
            JSONObject snap = snapshot();
            if (snap.optBoolean("ok")) { lastChat = snap; lastChatAt = now; }
        } catch (Exception ignored) {
        }
    }

    // ---------------- خواندن گفتگوی باز (واتس‌اپ، تلگرام، …) ----------------
    // فقط متن‌هایی خوانده می‌شوند که روی صفحه دیده می‌شوند؛ دیتابیس پیام‌رسان
    // خوانده نمی‌شود. جهت پیام (ارسالی/دریافتی) از جای حباب روی صفحه حدس زده
    // می‌شود و در زبان راست‌به‌چپ برعکس است.

    /** بسته‌های پیام‌رسان شناخته‌شده؛ خواندن دستی برای هر برنامه‌ی جلوی صفحه کار می‌کند */
    private static final String[] CHAT_PKGS = {
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.messenger.web"};

    private static boolean isChatPkg(String pkg) {
        for (String p : CHAT_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    public static volatile JSONObject lastChat = null;
    public static volatile long lastChatAt = 0;
    private static long lastAutoAt = 0;
    private static String accumKey = "";
    private static final java.util.ArrayList<JSONObject> accum = new java.util.ArrayList<>();
    private static final java.util.HashSet<String> accumSeen = new java.util.HashSet<>();

    /** خواندن پیام‌های روی صفحه‌ی فعلی */
    public static JSONObject readNow() {
        ControlService s = instance;
        if (s == null) return J.obj("ok", false, "error", "سرویس دسترسی‌پذیری فعال نیست");
        return s.snapshot();
    }

    /** اسکرول به بالا (پیام‌های قدیمی‌تر) و خواندن؛ پیام‌ها در انباشته نگه داشته می‌شوند */
    public static JSONObject more() {
        ControlService s = instance;
        if (s == null) return J.obj("ok", false, "error", "سرویس دسترسی‌پذیری فعال نیست");
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            AccessibilityNodeInfo sc = findScrollable(root);
            if (sc == null) return J.obj("ok", false, "error", "لیست قابل اسکرول پیدا نشد؛ گفتگو را باز کنید");
            sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            return J.obj("ok", false, "error", e.getMessage());
        }
        JSONObject snap = s.snapshot();
        if (!snap.optBoolean("ok")) return snap;
        String key = snap.optString("pkg") + "|" + snap.optString("title");
        if (!key.equals(accumKey)) { accumKey = key; accum.clear(); accumSeen.clear(); }
        JSONArray arr = snap.optJSONArray("messages");
        java.util.ArrayList<JSONObject> older = new java.util.ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String k = m.optString("text") + "|" + (m.optBoolean("out") ? "o" : "i");
            if (accumSeen.add(k)) older.add(m);
        }
        accum.addAll(0, older);
        return J.obj("ok", true, "pkg", snap.optString("pkg"), "app", snap.optString("app"),
                "title", snap.optString("title"), "messages", new JSONArray(accum),
                "count", accum.size(), "at", System.currentTimeMillis());
    }

    private JSONObject snapshot() {
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception e) {
            return J.obj("ok", false, "error", "خواندن صفحه ممکن نشد: " + e.getMessage());
        }
        if (root == null) return J.obj("ok", false, "error", "پنجره‌ای باز نیست");
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        android.graphics.Rect rb = new android.graphics.Rect();
        root.getBoundsInScreen(rb);
        int w = Math.max(1, rb.width()), h = Math.max(1, rb.height());
        boolean rtl = getResources().getConfiguration().getLayoutDirection()
                == android.view.View.LAYOUT_DIRECTION_RTL;
        java.util.ArrayList<JSONObject> msgs = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String[] title = {""};
        collect(root, w, h, rtl, msgs, seen, title);
        msgs.sort((a, b) -> Integer.compare(a.optInt("y"), b.optInt("y")));
        JSONObject snap = J.obj("ok", true, "pkg", pkg, "app", appLabel(pkg), "title", title[0],
                "messages", new JSONArray(msgs), "count", msgs.size(), "at", System.currentTimeMillis());
        lastChat = snap;
        lastChatAt = System.currentTimeMillis();
        return snap;
    }

    private void collect(AccessibilityNodeInfo n, int w, int h, boolean rtl,
                         java.util.ArrayList<JSONObject> msgs, java.util.HashSet<String> seen, String[] title) {
        if (n == null) return;
        try {
            // تلگرام: هر سلول پیام (ChatMessageCell) یک پیام است؛ متن‌های داخل
            // سلول (نام فرستنده، نقل‌قول، ساعت، تیک) جدا خوانده نشوند
            CharSequence cn = n.getClassName();
            if (cn != null && cn.toString().contains("ChatMessageCell")) {
                if (emitCell(n, w, h, rtl, msgs, seen)) return;
            }
            CharSequence t = n.getText();
            if (t != null) {
                String text = t.toString().trim();
                if (!text.isEmpty() && text.length() <= 2000 && !n.isEditable()) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    n.getBoundsInScreen(r);
                    int cx = r.centerX(), top = r.top;
                    if (top < h * 0.08 && title[0].isEmpty()) {
                        title[0] = text; // بالای صفحه: نام گفتگو
                    } else if (top > h * 0.08 && top < h * 0.88) {
                        if (!isTimeLike(text)) { // ساعت/وضعیت پیام نیست
                            String side = cx < w * 0.42 ? "left" : cx > w * 0.58 ? "right" : "center";
                            if (!side.equals("center")) { // وسط صفحه: سربرگ تاریخ و مانند آن
                                if (!dupNear(msgs, text, top)) {
                                    boolean out = rtl ? side.equals("left") : side.equals("right");
                                    msgs.add(J.obj("text", text, "out", out, "side", side, "y", top));
                                }
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), w, h, rtl, msgs, seen, title);
        } catch (Exception ignored) {
        }
    }

    /** همان متن در فاصله‌ی کم عمودی = همان پیام که دو بار در درخت آمده (پدر و فرزند) */
    private static boolean dupNear(java.util.ArrayList<JSONObject> msgs, String text, int top) {
        for (JSONObject m : msgs) {
            if (text.equals(m.optString("text")) && Math.abs(m.optInt("y") - top) < 50) return true;
        }
        return false;
    }

    /** ساعت خالص یا ساعت با تیک خوانده‌شدن؛ پیام نیست */
    private static boolean isTimeLike(String text) {
        if (text.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) return true;
        if (text.matches("\\d{1,2}:\\d{2}\\s*[✓✔]*")) return true;
        return false;
    }

    /** یک سلول پیام تلگرام را به یک پیام تبدیل می‌کند؛ false یعنی سلول متن
     *  قابل‌خواندنی نداشت و باید مثل بقیه‌ی گره‌ها خوانده شود */
    private boolean emitCell(AccessibilityNodeInfo cell, int w, int h, boolean rtl,
                             java.util.ArrayList<JSONObject> msgs, java.util.HashSet<String> seen) {
        java.util.ArrayList<String[]> parts = new java.util.ArrayList<>();
        for (int i = 0; i < cell.getChildCount(); i++) collectCellParts(cell.getChild(i), w, h, parts);
        if (parts.isEmpty()) return false;
        String[] best = null;
        for (String[] p : parts) {
            if (isTimeLike(p[0])) continue;
            if (best == null || p[0].length() > best[0].length()) best = p;
        }
        if (best == null) return true;
        int cx = Integer.parseInt(best[1]), top = Integer.parseInt(best[2]);
        String side = cx < w * 0.42 ? "left" : cx > w * 0.58 ? "right" : "center";
        if (side.equals("center")) return true;
        if (dupNear(msgs, best[0], top)) return true;
        boolean out = rtl ? side.equals("left") : side.equals("right");
        msgs.add(J.obj("text", best[0], "out", out, "side", side, "y", top));
        return true;
    }

    private void collectCellParts(AccessibilityNodeInfo n, int w, int h, java.util.ArrayList<String[]> parts) {
        if (n == null) return;
        try {
            CharSequence t = n.getText();
            if (t != null) {
                String text = t.toString().trim();
                if (!text.isEmpty() && text.length() <= 2000 && !n.isEditable()) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    n.getBoundsInScreen(r);
                    int top = r.top;
                    if (top > h * 0.08 && top < h * 0.88) {
                        parts.add(new String[]{text, String.valueOf(r.centerX()), String.valueOf(top)});
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) collectCellParts(n.getChild(i), w, h, parts);
        } catch (Exception ignored) {
        }
    }

    /** تخلیه‌ی خام درخت دسترسی‌پذیری برای عیب‌یابی (تلگرام و …) */
    public static JSONObject dumpTree() {
        ControlService s = instance;
        if (s == null) return J.obj("ok", false, "error", "سرویس دسترسی‌پذیری فعال نیست");
        AccessibilityNodeInfo root;
        try { root = s.getRootInActiveWindow(); } catch (Exception e) {
            return J.obj("ok", false, "error", e.getMessage());
        }
        if (root == null) return J.obj("ok", false, "error", "پنجره‌ای باز نیست");
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        dumpNode(root, 0, lines);
        return J.obj("ok", true, "pkg", pkg, "lines", new JSONArray(lines), "count", lines.size());
    }

    private static void dumpNode(AccessibilityNodeInfo n, int depth, java.util.ArrayList<String> lines) {
        if (n == null || lines.size() >= 300 || depth > 12) return;
        try {
            CharSequence cn = n.getClassName();
            CharSequence t = n.getText();
            String text = t == null ? "" : t.toString().replace("\n", "\\n");
            if (text.length() > 120) text = text.substring(0, 120) + "…";
            boolean interesting = !text.isEmpty()
                    || (cn != null && cn.toString().contains("ChatMessageCell"));
            if (interesting) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < depth; i++) sb.append("  ");
                sb.append(cn == null ? "?" : cn)
                        .append(" [").append(r.left).append(",").append(r.top)
                        .append(",").append(r.right).append(",").append(r.bottom).append("]");
                if (!text.isEmpty()) sb.append(" text=").append(text);
                lines.add(sb.toString());
            }
            for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, lines);
        } catch (Exception ignored) {
        }
    }

    /** بزرگ‌ترین گره‌ی قابل اسکرول (لیست پیام‌ها) */
    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        AccessibilityNodeInfo best = null;
        int bestArea = 0;
        java.util.ArrayDeque<AccessibilityNodeInfo> st = new java.util.ArrayDeque<>();
        st.push(n);
        while (!st.isEmpty()) {
            AccessibilityNodeInfo c = st.pop();
            try {
                if (c.isScrollable()) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    c.getBoundsInScreen(r);
                    int area = r.width() * r.height();
                    if (area > bestArea) { bestArea = area; best = c; }
                }
                for (int i = 0; i < c.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = c.getChild(i);
                    if (ch != null) st.push(ch);
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return l == null ? pkg : l.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ---------------- ژست‌ها (x,y بر حسب پیکسل واقعی صفحه) ----------------

    private boolean gesture(Path path, long start, long duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, start, Math.max(1, duration)))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean tap(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 0, ms);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, 0, Math.max(50, Math.min(3000, ms)));
    }

    public boolean global(String name) {
        int a;
        switch (name) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return false;
                a = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "power": a = GLOBAL_ACTION_POWER_DIALOG; break;
            default: return false;
        }
        return performGlobalAction(a);
    }

    // ---------------- تایپ ----------------

    /** کادر فعال؛ همیشه با refresh تا متن و مکان‌نما تازه از خود برنامه خوانده شود (نه از حافظه‌ی موقت) */
    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (n == null) return null;
        n.refresh();
        return n.isEditable() ? n : null;
    }

    /** راه دوم: چسباندن از کلیپ‌بورد در جای مکان‌نما */
    private boolean paste(AccessibilityNodeInfo n, String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PayamBin", s));
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean setText(AccessibilityNodeInfo n, CharSequence text, int cursor) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (ok && cursor >= 0) {
            Bundle s = new Bundle();
            s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
            s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s);
        }
        return ok;
    }

    /** متن فعلی کادر (بدون متن راهنما) */
    private static String currentText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null) return "";
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        return t.toString();
    }

    /** نوشتن متن در جای مکان‌نما */
    public synchronized String type(String s) {
        AccessibilityNodeInfo n = focusedEditable();
        if (n == null) return "روی گوشی هیچ کادر متنی انتخاب نشده؛ اول روی کادر کلیک کنید";
        String cur = currentText(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || a > cur.length()) a = cur.length();
        if (b < a || b > cur.length()) b = a;
        if (b < a) { int t = a; a = b; b = t; }
        String next = cur.substring(0, a) + s + cur.substring(b);
        if (setText(n, next, a + s.length())) {
            // بررسی: آیا برنامه همان متن را قبول کرد؟
            n.refresh();
            String now = currentText(n);
            if (now.equals(next) || now.contains(s)) return null;
            // برنامه متن را تغییر داد/رد کرد → متن قبلی را برگردان و با چسباندن امتحان کن
            setText(n, cur, a);
            n.refresh();
        }
        if (paste(n, s)) return null;
        return "نوشتن در این کادر ممکن نشد";
    }

    public synchronized String key(String k) {
        AccessibilityNodeInfo n = focusedEditable();
        switch (k) {
            case "Backspace":
            case "Delete": {
                if (n == null) return null;
                String cur = currentText(n);
                int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
                if (a < 0 || a > cur.length()) a = cur.length();
                if (b < a || b > cur.length()) b = a;
                if (a != b) return setText(n, cur.substring(0, a) + cur.substring(b), a) ? null : "ناموفق";
                if (k.equals("Backspace")) {
                    if (a == 0) return null;
                    int cut = Character.isLowSurrogate(cur.charAt(a - 1)) && a >= 2 ? 2 : 1;
                    return setText(n, cur.substring(0, a - cut) + cur.substring(a), a - cut) ? null : "ناموفق";
                } else {
                    if (a >= cur.length()) return null;
                    return setText(n, cur.substring(0, a) + cur.substring(a + 1), a) ? null : "ناموفق";
                }
            }
            case "Enter":
                if (n != null && Build.VERSION.SDK_INT >= 30) {
                    if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return null;
                }
                return n != null ? type("\n") : null;
            case "SelectAll":
                if (n == null) return null;
                Bundle s = new Bundle();
                s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText(n).length());
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s);
                return null;
            case "ArrowLeft":
            case "ArrowRight": {
                if (n == null) return null;
                Bundle m = new Bundle();
                m.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
                m.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
                // در متن فارسی (راست‌به‌چپ) جهت‌ها برعکس است؛ ساده: چپ = عقب
                n.performAction(k.equals("ArrowLeft") ? AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                        : AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, m);
                return null;
            }
            default:
                return null;
        }
    }
}
