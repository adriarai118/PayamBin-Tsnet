package com.adriarai.payambin;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

/** فهرست مخاطبین با شماره‌ها */
public final class ContactsRepo {
    private ContactsRepo() {}

    public static JSONArray list(Context ctx) {
        JSONArray out = new JSONArray();
        if (!SmsRepo.has(ctx, Manifest.permission.READ_CONTACTS)) return out;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
            if (c == null) return out;
            String lastName = null;
            JSONArray nums = null;
            while (c.moveToNext()) {
                String name = c.getString(0);
                String num = c.getString(1);
                if (name == null) name = "";
                name = name.trim();
                if (num == null) num = "";
                num = num.trim();
                if (name.isEmpty() && num.isEmpty()) continue;
                if (name.isEmpty()) name = num;
                if (!name.equals(lastName)) {
                    JSONObject cur = J.obj("name", name, "numbers", new JSONArray());
                    out.put(cur);
                    nums = cur.optJSONArray("numbers");
                    lastName = name;
                }
                if (!num.isEmpty() && !hasNum(nums, num)) nums.put(num);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean hasNum(JSONArray nums, String num) {
        if (nums == null) return false;
        for (int i = 0; i < nums.length(); i++) if (num.equals(nums.optString(i))) return true;
        return false;
    }
}
