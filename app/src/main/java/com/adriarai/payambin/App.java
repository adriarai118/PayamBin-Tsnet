package com.adriarai.payambin;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Crash.install(this);
        // نگهبان سرور را مسلح کن (زنجیره‌ی بررسی فقط وقتی ادامه دارد که سرور باید روشن باشد)
        try {
            Watchdog.schedule(this);
        } catch (Exception ignored) {
        }
    }
}
