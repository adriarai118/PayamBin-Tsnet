# Tailscale در پیام‌بین (tsnet — نسخهٔ آزمایشی)

> ⚠️ نسخهٔ آزمایشی، فقط در همین ریپوی جدا (`PayamBin-Tsnet`).
> ریپوی اصلی PayamBin دست نخورده است. اتصال سرتاسری روی tailnet
> هنوز روی گوشی واقعی تست نشده است — فهرست تست را در
> `TSNET-BUILD-LOG.md` ببینید.

## ایده در یک جمله

فقط خودِ پیام‌بین به tailnet وصل می‌شود؛ نه کل گوشی، نه با VPN سیستم.
یک گرهٔ Tailscale (کتابخانهٔ `tailscale.com/tsnet`) داخل خودِ برنامه
اجرا می‌شود، روی همان پورت وب‌سرور گوش می‌دهد و هر اتصال رسیده از
tailnet را به وب‌سرور محلی (`127.0.0.1:8080`) پروکسی می‌کند.

## چرا tsnet و نه اپ کامل Tailscale؟

| | tsnet (همین روش) | کپی اپ Tailscale |
|---|---|---|
| VPN سیستم (`VpnService`) | لازم نیست | لازم است |
| تداخل با VPNهای دیگر | ندارد | دارد (اندروید فقط یک VPN فعال دارد) |
| نصب برنامهٔ دوم | لازم نیست | لازم است |
| کد لازم | یک ماژول Go کوچک + چند فایل جاوا | کل کلاینت Tailscale |

## معماری

```
کامپیوتر/گوشی دوم (روی tailnet)
        │  https://payambin:8080  (آدرس 100.x)
        ▼
┌─────────────────────────────────────────────┐
│ گوشی (PayamBin)                             │
│  tsnet.Server (Go، داخل پروسس برنامه)       │
│    Listen("tcp", ":8080") روی tailnet       │
│        │  proxy (io.Copy دوطرفه)            │
│        ▼                                     │
│  WebServer.java (بدون تغییر)                 │
│    ServerSocket روی 0.0.0.0:8080             │
│    Net.isLocal() قبلاً 100.64–100.127 را     │
│    قبول می‌کند، پس هم‌تیمی‌های tailnet رد     │
│    نمی‌شوند                                  │
└─────────────────────────────────────────────┘
```

فایل‌ها:

- `tsnet/go/mobile.go` — بستهٔ Go (`payambin-tsnet`، بستهٔ `tsnetmobile`):
  `Start(hostname, authKey, stateDir, localPort)`، `Status()` (JSON)،
  `Stop()`، `IsRunning()`، `TailscaleIP()`. فقط رشته/عدد/bool از مرز
  gomobile رد می‌شود؛ `net.Conn` هرگز رد نمی‌شود.
- `tsnet/go/go.mod` — `tailscale.com v1.104.7` (نیاز به Go ‏1.24+؛
  اینجا با Go ‏1.27.4 ساخته شده).
- `app/libs/tsnet.aar` — خروجی `gomobile bind`؛ اگر نباشد، بیلد
  معمولی برنامه خراب نمی‌شود (به `app/build.gradle` نگاه کنید).
- `app/src/main/java/com/adriarai/payambin/TailscaleManager.java` —
  کلاس Go را با reflection صدا می‌زند؛ اگر AAR نباشد یا دستگاه
  اندروید ۸ (API 26) پایین‌تر باشد، «در دسترس نیست» گزارش می‌دهد.
- `Prefs.java` — کلیدهای جدید: `tsnet_enabled` (پیش‌فرض خاموش)،
  `tsnet_hostname` (پیش‌فرض `payambin`)، `tsnet_auth_key` (پیش‌فرض خالی).
- `ServerService.java` — بعد از روشن‌شدن WebServer، اگر فعال باشد
  `TailscaleManager.start()` را صدا می‌زند؛ در `onDestroy` متوقفش
  می‌کند. وقتی خاموش است رفتار LAN دقیقاً مثل قبل است.
- `MainActivity.java` — بخش «🌐 Tailscale (دسترسی از اینترنت)» در
  صفحهٔ تنظیمات: سوییچ، خط وضعیت، نام دستگاه، کلید ورود، دکمهٔ
  «باز کردن لینک ورود».

## ورود به Tailscale (یک بار برای هر گوشی)

دو راه هست؛ هر دو به حساب Tailscale نیاز دارند:

1. **ورود تعاملی (پیشنهادشده):** کلید ورود را خالی بگذارید، سوییچ
   Tailscale را روشن کنید؛ وضعیت «نیاز به ورود» می‌شود و دکمهٔ
   «باز کردن لینک ورود» لینک `login.tailscale.com` را باز می‌کند.
   بعد از ورود در مرورگر، گره ثبت می‌شود و وضعیتش در حافظهٔ
   برنامه (`files/tsnet/`) می‌ماند؛ دفعات بعد بدون ورود بالا می‌آید.
2. **کلید ورود (auth key):** از کنسول مدیریت Tailscale
   (Settings ← Keys) یک کلید بسازید و در برنامه وارد کنید.
   کلید در SharedPreferences خصوصی برنامه نگه داشته می‌شود
   (همان سطح رمز ورود)؛ **هرگز در کد، گیت‌هاب یا چت ننویسید.**
   کلید یک‌بارمصرف/منقضی‌شونده امن‌تر است؛ بعد از ثبت گره،
   کلید دیگر لازم نیست و می‌توانید آن را از تنظیمات پاک کنید.

نام دستگاه پیش‌فرض `payambin` است؛ آدرس اینترنتی بعد از اتصال:
`http://payambin:8080` (یا با نام کامل MagicDNS). پورت همان پورت
وب‌سرور است و از تنظیمات سرور تغییر می‌کند.

## ساخت AAR

ابزار لازم (در این ماشین زیر `~/workspace/toolchains/` نصب شده‌اند):
Go ‏1.27.4، Temurin JDK ‏17، Android SDK (platform 34،
build-tools 34.0.0، NDK ‏27.2.12479018).

```sh
export PATH=$HOME/workspace/toolchains/go/bin:$PATH
export GOPATH=$HOME/workspace/toolchains/gopath
export JAVA_HOME=$HOME/workspace/toolchains/jdk17
export ANDROID_HOME=$HOME/workspace/toolchains/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

cd tsnet/go
go mod tidy
go vet ./...

go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android -androidapi 26 \
  -javapkg=com.adriarai.payambin.tsnet \
  -o ../../app/libs/tsnet.aar .
```

بعد از ساخت AAR، بیلد عادی اندروید:

```sh
export ANDROID_HOME=$HOME/workspace/toolchains/android-sdk
export JAVA_HOME=$HOME/workspace/toolchains/jdk17
./gradlew :app:assembleDebug   # یا assembleRelease
```

> ⚠️ در ماشینی که این نمونه آنجا ساخته شد، Gradle اجرا نمی‌شود:
> سندباکس TCP خام جاوا را می‌بندد (`other_tcp = Deny`) و دیمن Gradle
> با کلاینتش روی سوکت localhost حرف می‌زند؛ خطا:
> `Could not receive a message from the daemon` /
> `Unexpected type tag 109 found`. این مشکل کد نیست؛ روی ماشین
> معمولی یا در GitHub Actions بیلد Gradle باید کار کند. در آن
> ماشین، بیلد دستی جایگزین با `aapt2`/`javac`/`d8`/`apksigner`
> انجام و APK امضاشده ساخته شد — جزئیات در `TSNET-BUILD-LOG.md`.
> (فایل `app/libs/tsnet.aar` ساخته شده: ۶۳ مگابایت، هر ۴ ABI.)

اگر `app/libs/tsnet.aar` نباشد، `app/build.gradle` وابستگی را
اضافه نمی‌کند و برنامه بدون Tailscale (فقط LAN) ساخته می‌شود.

## محدودیت‌ها

- برای هر گوشی یک بار ورود به Tailscale لازم است.
- صفحهٔ زنده و دوربین زنده روی اینترنت کندترند و دادهٔ موبایل
  مصرف می‌کنند؛ پیامک، اعلان و فایل مشکلی ندارند.
- ترافیک از سرورهای Tailscale (DERP) رد می‌شود؛ مثل هر VPN،
  به حساب Tailscale خودتان اعتماد می‌کنید.
- اندروید ۸ (API 26) به بالا لازم است؛ روی اندروید قدیمی‌تر
  گزینهٔ Tailscale «پشتیبانی نمی‌شود» نشان داده می‌شود.
- وقتی Tailscale روشن است، فقط دستگاه‌های همان tailnet (با همان
  حساب، یا دعوت‌شده) به پیام‌بین می‌رسند؛ بقیهٔ اینترنت نه.

## نصب کنار نسخهٔ اصلی (side-by-side)
این بیلد عمداً یک برنامهٔ جداست، نه آپدیت نسخهٔ اصلی:
- `applicationId` برابر `com.adriarai.payambin.tsnet` است (namespace
  جاوا همان `com.adriarai.payambin` مانده)؛ پس کنار نسخهٔ اصلی نصب
  می‌شود و آن را رونویسی نمی‌کند.
- نام برنامه در لانچر «پیام‌بین Tsnet» است.
- FileProvider با authority جدا (`...tsnet.files`) ثبت شده؛ وگرنه
  اندروید نصب دوم را رد می‌کرد.
- آدرس mDNS جدا: `payambin-tsnet.local`؛ نام پیش‌فرض گرهٔ Tailscale
  هم `payambin-tsnet` است.
- آپدیتر داخلی به ریلیزهای همین ریپو (`PayamBin-Tsnet`) نگاه می‌کند،
  نه ریپوی اصلی.
- توجه: هر دو برنامه روی پورت 8080 گوش می‌دهند؛ پس هر دو را هم‌زمان
  روشن نکنید — دومی پورت را نمی‌گیرد.

## پورت جدا (9191)
پورت پیش‌فرض وب‌سرور در این بیلد 9191 است (نه 8080 نسخهٔ اصلی) و
پورت پروکسی 9192 (نه 8888) — پس هر دو برنامه می‌توانند هم‌زمان روشن
باشند. آدرس‌ها: LAN روی `http://payambin-tsnet.local:9191` و روی
tailnet روی `http://payambin-tsnet:9191`. پورت از تنظیمات برنامه هم
قابل تغییر است.

## چرا روی گوشی «permission denied» می‌داد
اندروید ۱۱ به بعد به برنامه‌های معمولی اجازه‌ی netlink نمی‌دهد و
`net.Interfaces()` در Go با خطای `route ip+net: netlinkrib: permission
denied` می‌میرد؛ tsnet موقع بالا آمدن همان را صدا می‌زند. ربطی به
کلید ورود ندارد. راه‌حل: `tsnet/go/android_ifaces.go` با
`netmon.RegisterInterfaceGetter` لیست اینترفیس‌ها را بدون netlink
(ioctl + ‎`/sys/class/net` + ‎`/proc/net/if_inet6`) می‌سازد.

### اصلاح دوم (20 Nov 2026): /sys/class/net هم بسته است
بعد از اصلاح netlink، خطا شد `read /sys/class/net: permission denied` —
روی این گوشی حتی خواندن دایرکتوری /sys/class/net ممنوع است. پس لیست
اینترفیس‌ها حالا از API اندروید می‌آید: `TailscaleManager` با
`NetworkInterface.getNetworkInterfaces()` لیست را جمع می‌کند و قبل از
شروع، با `SetInterfacesJSON` به Go می‌دهد؛ getter همین لیستِ cacheشده
را برمی‌گرداند و /sys فقط fallback است.
