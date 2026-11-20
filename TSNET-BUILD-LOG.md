# TSNET build log — PayamBin-Tsnet prototype

Local copy only: `~/workspace/payambin-tsnet`. Nothing pushed to GitHub.
Original PayamBin repo untouched. WebServer.java and Net.java unchanged
(`git diff` on them is empty).

## Toolchains (installed under ~/workspace/toolchains/)
- Go 1.27.4 (`go version go1.27.4 linux/amd64`) — downloaded from go.dev
- Temurin JDK 17.0.21.1
- Android SDK cmdline-tools 13114758; platform-tools r37.0.3,
  platforms;android-34 (ext7 r03), build-tools 34.0.0, NDK 27.2.12479018
  (r27c) — downloaded directly from dl.google.com with curl, because
  `sdkmanager` could not fetch its remote manifest in this VM
  (proxy-related `HttpURLConnection.doTunneling` failure in the licenses
  step; unsetting the proxy vars let `--licenses` pass, but package
  install still failed with "IO exception while downloading manifest").
  Note: the platform-34 and build-tools zips extract one level too deep
  (`platforms/android-34/android-34/`, `build-tools/34.0.0/android-14/`);
  contents were moved up one level.
- Gradle 8.9 (installed binary; the project has no gradlew wrapper).
- tailscale.com v1.104.7 (`go get tailscale.com@latest`); module
  `payambin-tsnet`, `go 1.27.4`. `go mod tidy` completed (go.sum present).

## Go module — PASS
- `gofmt -l .` after `gofmt -w mobile.go`: clean.
- `go vet ./...`: **PASS, exit 0** (empty output).
  - First attempt FAILED with `no space left on device` writing vet.cfg:
    /tmp is a 512M tmpfs and the tailscale build cache overflowed it.
    (The `VET_EXIT=0` in that run was the pipeline's `tail`, not vet.)
    Fix: `GOCACHE`/`TMPDIR` moved to `~/workspace/toolchains/gocache`
    and `~/workspace/toolchains/tmp`; re-run passed cleanly.
- `gomobile bind` -> `app/libs/tsnet.aar`: **PASS, exit 0** (second run).
  - First bind run (35 min of native compiles) failed at the final javac
    step only: `javac: executable file not found in $PATH` — JAVA_HOME
    was set but its `bin/` was not on PATH. Re-run with JDK bin on PATH
    finished in ~90 s (Go builds cached).
  - `gomobile bind -target=android -androidapi 26
    -javapkg=com.adriarai.payambin.tsnet -o ../../app/libs/tsnet.aar .`
  - AAR: 63,021,997 bytes; jni/ for all 4 ABIs (armeabi-v7a 40 MB,
    arm64-v8a 31 MB, x86 41 MB, x86_64 33 MB, uncompressed).
  - Generated class (verified with javap):
    `com.adriarai.payambin.tsnet.tsnetmobile.Tsnetmobile` with
    `start(String,String,String,long)`, `status()`, `stop()`,
    `isRunning()`, `tailscaleIP()` — lowercase method names, nested
    `tsnetmobile` package. TailscaleManager was fixed to match
    (first version assumed `...tsnet.Tsnetmobile` / `Start` — wrong).
  - `Seq.setContext` is NOT needed: our Go code never calls back into
    the JVM (setContext is only for RunOnJvm).

## Gradle — FAILED (environment blocker, not a code problem)
- Command: `gradle :app:assembleDebug --no-daemon` (Gradle 8.9, JDK 17)
  and the same in persistent-daemon mode.
- Exact error (both modes, ~7 s in):
  ```
  FAILURE: Build failed with an exception.
  * What went wrong:
  Could not receive a message from the daemon.
  Caused by: ... MessageIOException: Could not read message from '/127.0.0.1:36571'.
  Caused by: java.lang.IllegalArgumentException: Unexpected type tag 109 found.
  ```
- Root cause, proven with a minimal test: a Java client connecting to
  a working Python server on 127.0.0.1 receives the sandbox policy
  message `muse: Other TCP connections is turned off for this assistant.
  To allow it, ask the user to open Muse settings -> Permissions ->
  Direct network protocols and switch other_tcp from Deny to Ask.`
  Byte 109 is 'm', the first byte of that message. Python loopback TCP
  works; Java raw TCP (any destination, including localhost) is
  intercepted. The Gradle daemon communicates with its client over a
  localhost TCP socket, so Gradle cannot run in this VM at all.
  The daemon itself starts fine (per its log) — only the client<->daemon
  socket is blocked. This is a sandbox policy, not something to bypass.
  On a normal machine / GitHub Actions the Gradle build should work;
  the repo's own CI workflow (`gradle assembleRelease`) is the place
  that will exercise it. Alternatively the user can switch other_tcp
  from Deny to Ask in Muse settings and re-run the Gradle build here.

## Manual Android build (no Gradle daemon) — PASS
Script: `~/workspace/toolchains/tmp/manual-build.sh`
(aapt2 compile/link + javac + d8 + zipalign + apksigner).
- aapt2 compile resources: OK. aapt2 link: OK after two fixes —
  the repo manifest has no `package` attribute (AGP reads `namespace`
  from build.gradle), so the script links a temp copy with
  `package="com.adriarai.payambin"` added (repo manifest untouched);
  `--version-code 1 --version-name "1.1-tsnet"`.
- `javac` WITH tsnet AAR: **OK, 55 classes** (needs `-encoding UTF-8`
  for the Persian comments; Gradle sets the same encoding).
- `javac` WITHOUT tsnet AAR: **OK, 55 classes** — the fallback path
  (TailscaleManager via reflection) compiles with no AAR present.
- d8 dex: OK (228,032-byte classes.dex); needed the classes jarred
  first and `java` on PATH.
- APK assembly: OK after fixing AAR entries' 1979 timestamps
  (Python zipfile rejects pre-1980 dates).
- Signed APK (all 4 ABIs): `~/workspace/toolchains/tmp/manual-build/PayamBin-Tsnet-debug.apk`,
  144,897,718 bytes. `apksigner verify`: v1+v2+v3 true, 1 signer.
  `aapt2 dump badging`: package com.adriarai.payambin, versionCode 1,
  versionName 1.1-tsnet, compileSdk 34.
- arm64-only APK: `~/workspace/toolchains/tmp/manual-build/PayamBin-Tsnet-arm64.apk`,
  14,964,200 bytes, apksigner verify OK — the practical one to sideload.
- Dex string check confirms the wiring is in the APK:
  `TailscaleManager`, `com.adriarai.payambin.tsnet.tsnetmobile.Tsnetmobile`,
  `tsnet_enabled` / `tsnet_hostname` / `tsnet_auth_key`.

## Behaviour check
- Tailscale disabled (default): LAN behaviour unchanged — ServerService
  only calls TailscaleManager.start() when `tsnet_enabled` is true, and
  TailscaleManager no-ops when the AAR is absent (reflection) or the
  device is below API 26.
- WebServer.java: unchanged. Net.java isLocal(): unchanged (100.x
  already accepted).

## NOT tested
- End-to-end tailnet connectivity: NO phone and NO Tailscale account
  available in this VM. The login flow, the tailnet listener, and the
  proxy to 127.0.0.1:8080 have NOT been verified on a real device.
- The Gradle/AGP build itself (see "Gradle — FAILED" above): the
  manual build above is the substitute verification in this VM.

## CI fix — 20 Nov 2026 (after run #13 failed)
- GitHub Actions run #13 (commit 698cf3f) FAILED in "Build tsnet AAR":
  `gomobile bind requires golang.org/x/mobile in the current module`.
  Fix: `go get -tool golang.org/x/mobile/cmd/gobind` in tsnet/go —
  go.mod now has `tool golang.org/x/mobile/cmd/gobind` (x/mobile
  v0.0.0-20261117202452-47699113848a; also upgraded x/crypto v0.59.0,
  x/net v0.63.0, x/term v0.48.0, x/text v0.44.0). `go vet ./...` PASS
  again after the change. Only go.mod/go.sum changed for this fix.
- Note: releases v8/v9 APKs were only 159 KB — do not trust them as
  tsnet builds; v9 predates the tsnet code entirely.

## CI fix 2 — 20 Nov 2026 (runs #14/#15 failed)
- Run #15 (commit 5fad148, after the gobind tool fix) FAILED in
  "Build tsnet AAR" (10m 56s): `gomobile: open ../../app/libs/tsnet.aar:
  no such file or directory`. Cause: app/libs/ does not exist in a
  fresh checkout (the AAR is a build artifact, not committed), and
  gomobile bind does not create the output directory.
  Fix: `mkdir -p ../../app/libs` in the workflow before gomobile bind.
  Run #14 (commit 1c3efe6) failed the same way (11m 45s).

## CI fix 3 — 20 Nov 2026 (run #17 failed)
- "Build tsnet AAR" step SUCCEEDED (11m 45s) — the mkdir fix worked.
  Next failure was Gradle manifest merger:
  `uses-sdk:minSdkVersion 23 cannot be smaller than version 26
  declared in library [tsnet.aar]`.
  Fix: app minSdk 23 -> 26 in app/build.gradle (tsnet/gomobile needs
  API 26+ anyway; TailscaleManager already guarded for API < 26).
  Consequence: this build no longer installs on Android 5-7.1
  (API 23-25). Only the separate PayamBin-Tsnet repo is affected.

## Side-by-side — 20 Nov 2026
- User reported the v19 APK wanted to update (replace) the original
  PayamBin instead of installing alongside it (same applicationId).
  Fix in the separate repo only: applicationId ->
  com.adriarai.payambin.tsnet; app label -> "پیام‌بین Tsnet";
  FileProvider authority -> ...tsnet.files (manifest + FilesProvider);
  Mdns HOST -> payambin-tsnet.local; default Tailscale hostname ->
  payambin-tsnet; Updater API -> PayamBin-Tsnet releases.
  Java package/namespace unchanged. Both apps listen on port 8080,
  so do not run both servers at the same time.

## Port change — 20 Nov 2026
- User asked for a different port, e.g. 9191. Default web port in the
  Tsnet build is now 9191 (was 8080) and default proxy port 9192
  (was 8888), in Prefs.java only — WebServer, ServerService display
  and the tsnet listener/proxy all read Prefs.port, so one change
  covers LAN + tailnet. Both apps can now run at the same time.

## netlink fix — 20 Nov 2026 (Tailscale "permission denied" on the phone)
- The phone showed `tsnet.Up: tsnet: route ip+net: netlinkrib:
  permission denied`. This is NOT an auth-key problem: since Android
  11 an ordinary app UID cannot bind a NETLINK_ROUTE socket, so Go's
  net.Interfaces() fails (golang/go#40569), and tsnet's netmon calls
  it during Up() (netmon.New -> interfaceState) — fatal.
- Fix in tsnet/go/android_ifaces.go: register Tailscale's own escape
  hatch, netmon.RegisterInterfaceGetter, with a getter that uses no
  netlink at all — interface list from /sys/class/net, IPv4 via
  SIOCGIFADDR/SIOCGIFNETMASK ioctls, IPv6 from /proc/net/if_inet6,
  addresses as netmon.Interface.AltAddrs. Registered only on Android.
  Also fixed a latent bug: after an Up() error the Go `running` flag
  stayed true, so Start() could never retry; it is now cleared on
  terminal errors. go vet PASS; gomobile bind rebuilt the AAR (61 MB);
  manual APK build + apksigner verify OK.

## 20 Nov 2026 — /sys/class/net هم روی گوشی مصطفی بسته است؛ لیست از جاوا می‌آید
- اسکرین‌شات ساعت 08:58: خطا عوض شد و شد
  `tsnet.Up: tsnet: read /sys/class/net: open /sys/class/net: permission denied`
  یعنی بیلد v26 (با اصلاح netlink) نصب شده و getter اجرا می‌شود، اما
  روی این گوشی حتی خواندن دایرکتوری /sys/class/net هم ممنوع است.
- راه‌حل (همان راه برنامهٔ رسمی Tailscale): لیست اینترفیس‌ها از API
  اندروید گرفته می‌شود. TailscaleManager.collectInterfacesJson() با
  java.net.NetworkInterface.getNetworkInterfaces() لیست را می‌سازد
  (نام، index، MTU، MAC، up/loopback، آدرس‌ها با prefix length) و
  قبل از Start با setInterfacesJSON به Go می‌دهد.
- Go: تابع صادرشدهٔ SetInterfacesJSON در tsnet/go/android_ifaces.go
  JSON را parse و cache می‌کند؛ getter اول cache جاوا را برمی‌گرداند
  و فقط اگر خالی بود سراغ /sys + ioctl می‌رود.
- بررسی: go vet PASS؛ gomobile bind PASS؛ javap متد
  setInterfacesJSON را در Tsnetmobile نشان می‌دهد؛ بیلد دستی
  MANUAL_BUILD_OK و apksigner verify OK (package
  com.adriarai.payambin.tsnet).

## 20 Nov 2026 — تکنیک خودِ PayamBin برای کارت شبکه (اندروید ۱۶)
- مصطفی گفت خطا هنوز هست و پیشنهاد کرد از تکنیک خود PayamBin
  (Net.localIps) استفاده شود. Net.localIps با
  NetworkInterface.getNetworkInterfaces() + getInetAddresses کار
  می‌کند و برای هر اینترفیس try/catch جدا دارد.
- اشکال نسخهٔ v30: در collectInterfacesJson قبلی یک استثنا (مثلاً
  getMTU روی یک اینترفیس خاص) کل JSON را خالی می‌کرد و Go دوباره
  سراغ /sys می‌رفت. حالا دقیقاً مثل Net: برای هر اینترفیس و هر
  خاصیت try/catch جدا، prefix از getInterfaceAddresses با fallback
  ‎/32 یا /128، حذف zone (%wlan0) از آدرس IPv6، و نتیجهٔ
  SetInterfacesJSON در Diag.log نوشته می‌شود.
- گوشی مصطفی اندروید ۱۶ است — همان‌جا /sys/class/net بسته است و
  API رسمی NetworkInterface تنها راهِ درست است.
- بررسی: go vet PASS؛ gomobile bind PASS؛ بیلد دستی
  MANUAL_BUILD_OK و apksigner verify OK.

## 20 Nov 2026 — APK دستی نصب نمی‌شد: «not compatible» (اندروید ۱۶)
- علت: اسکریپت manual-build.sh به aapt2 link نه min-sdk و نه
  target-sdk می‌داد؛ مانیفستِ APK دستی هیچ uses-sdk نداشت و
  targetSdk پیش‌فرض ۱ می‌شد. اندروید ۱۵ به بعد نصبِ برنامه با
  targetSdk زیر ۲۴ را نمی‌پذیرد — پیام «app isn't compatible».
  (احتمالاً هیچ‌کدام از APKهای دستی قبلی هم نصب نشده بودند و مصطفی
  همیشه با بیلدهای CI/آپدیتر تست می‌کرد.)
- اصلاح: ‎--min-sdk-version 26 --target-sdk-version 34 به aapt2
  link اضافه شد؛ حالا badging می‌گوید sdkVersion 26 و
  targetSdkVersion 34 و apksigner verify OK است.

## 20 Nov 2026 — کرش (force stop) موقع روشن کردن سوییچ
- روی POCO با اندروید ۱۶، به‌محض روشن کردن سوییچ برنامه force stop
  می‌شد؛ تومب‌استون فقط فریم ‎runtime.raise را نشان می‌داد
  (BuildId مطابق v33 بود). علت دقیق بدون Abort message مشخص نیست.
- اصلاح: runNode حالا panic را recover می‌کند و به‌جای کشتن برنامه،
  آن را به‌صورت خطا در وضعیت نشان می‌دهد («panic: ...») تا دلیلش
  در خود برنامه دیده شود. pollStatus هم recover دارد. همچنین در
  ipv6AddrsByIndex یک guard اضافه شد (خط‌های کوتاه‌تر از ۳۲ کاراکتر
  رد می‌شوند تا slice panic ندهد).
- بررسی: go vet PASS؛ gomobile bind PASS؛ بیلد دستی
  MANUAL_BUILD_OK و apksigner verify OK (sdkVersion 26،
  targetSdkVersion 34).
