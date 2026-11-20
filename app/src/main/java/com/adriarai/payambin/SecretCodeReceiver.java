package com.adriarai.payambin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** کد مخفی شماره‌گیر (*#*#7777#*#*) برنامه را باز می‌کند — حتی وقتی آیکن مخفی است */
public class SecretCodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            Intent m = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(m);
        } catch (Exception ignored) {
        }
    }
}
