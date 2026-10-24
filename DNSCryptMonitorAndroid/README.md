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
- Parsed new domains are stored in app-private SQLite before notification. Clearing the log does not clear seen history.
- Notifications have an Allow action and a five-second auto-dismiss timeout. If untouched, the domain is not allowed.
- Android 13+ notification permission is requested at first launch. Disable battery optimization if Android repeatedly stops monitoring.

## Build
Open this folder in Android Studio, sync Gradle, then Build > Build APK(s). Install the resulting debug APK and grant root access in KernelSU.

## Notes
- Allow appends to `allowed-names.txt`; restart the proxy to apply the list.
- Clearing the log truncates it and requests a service restart.
- Purging the seen database allows previously seen domains to notify again.
- This is source code, not a prebuilt APK.
