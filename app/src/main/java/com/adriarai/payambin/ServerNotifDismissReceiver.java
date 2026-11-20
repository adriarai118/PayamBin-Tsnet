package com.adriarai.payambin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** وقتی کاربر اعلان سرور را کنار می‌زند، ثبت کن تا سرویس دوباره آن را نفرستد */
public class ServerNotifDismissReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs.setServerNotifDismissed(c, true);
    }
}
