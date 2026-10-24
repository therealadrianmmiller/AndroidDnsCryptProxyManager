# DNSCrypt Monitor for Android

Native Kotlin / Jetpack Compose Android Studio project based on the supplied Python monitor. No SSHFS or remote mounting is included. This app targets a rooted Android device and executes file/service commands with `su`.

## Configure first
The default directory `/data/adb/dnscrypt-proxy` is a placeholder. In Settings, set the actual local directory containing:
- `blocked-names.log`
- `allowed-names.txt`
- `blocked-names.txt`

The restart action uses `setprop ctl.restart dnscrypt-proxy`. Set the Android init service name to the real service declared by your installation. This only works when dnscrypt-proxy is managed as an Android init service. If your root module instead provides a restart script, configure the source code to call that script; do not use `pkill` alone because it may leave the proxy stopped.

## Notifications
- Foreground service tails the log from its current end, so old entries are not notified on startup.
- Parsed domains are stored in app-private SQLite: a seen table prevents duplicate notifications, and a separate recent-blocked history survives log clearing.
- Blocked-domain notifications are not automatically removed; Android controls how long the heads-up popup appears, while the notification remains in the notification shade until dismissed. The Recent tab is a second chance to allow domains.
- Android 13+ notification permission is requested at first launch. Disable battery optimization if Android repeatedly stops monitoring.

## Build
Open this folder in Android Studio, sync Gradle, then Build > Build APK(s). Install the resulting debug APK and grant root access in KernelSU.

## Notes
- Allow appends to `allowed-names.txt` and requests a restart of the configured Android init service.
- Clearing the log truncates it and requests a service restart.
- Purging the seen database allows previously seen domains to notify again.
- This is source code, not a prebuilt APK.
