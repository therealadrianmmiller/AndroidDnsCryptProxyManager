# DNSCrypt Monitor for Android

![screenshot](media/dnscrypt-manager-dashboard_result.png)

This is a Dnscrypt Proxy Manager for Android that recreates the functions i use in my Windows (python) version

It is a personal build that satisfies my needs, and may be useful for others



## Functions:

Dsncrypt data files path: using /storage/emulated/0/dnscrypt-proxy  (sdcard/dnscrypt-proxy) as the path for log and associated block and allow lists
Note: the path is changeable in Settings

### Main function:

![screenshot](media/dnscrypt-manager-shade_result.png)
![screenshot](media/dnscrypt-manager-shade2_result.png)
![screenshot](media/dnscrypt-manager-shade3_result.png)

- Monitors the blocked-names.log file and 
  - parses and displays the blocked domain as a notification with Allow action (and also populates the domains in the Recents tab of app in case you dismiss or miss the notification)
  - service tails the log from its current end, so old entries are not notified on startup.
  - parsed domains are stored in a database: a seen table prevents duplicate notifications, and a separate recent-blocked history survives log clearing.
  - blocked-domain notifications are not automatically removed; Android controls how long the heads-up popup appears, while the notification remains in the notification shade until dismissed. The Recent tab is a second chance to allow domains.
  - allow action writes the domain to allowed-names.txt and restarts dnscrypt-proxy cleanly

Note: Android 13+ notification permission is requested at first launch (if not installed by the magisk module). Disable battery optimization if Android repeatedly stops monitoring..in my testing this has been unnecessary
 

### App Tabs:

**Dashboard**

![screenshot](media/dnscrypt-manager-dashboard_result.png)

- Start/Resume monitoring (Monitoring begins after boot)
- Stop Monitoring
- Restart dnscrypt-proxy
- Pause dnscrypt-proxy (useful when you really need to access a site and dont want to deal with finding the blocked domains) - dnscrypt-proxy remains running, but traffic is routed via normal DNS, and of course returned via dnscrypt-proxy when resumed.
- Clear blocked-names.log & Restart
- Purge seen & recent db & restart
- Refresh recent log/database count - show at bottom of screen after pressing

**Recent**

![screenshot](media/dnscrypt-manager-recents_result.png)

- Shows up to 200 (uniqued) domain names, latest first
- Each domain shown has the option to Allow & Restart dnscrypt-proxy, as well as (as backup/optionally) Remove the domain from blocked-names.txt
- Refresh the list of blocked domains
- Restart dnscrypt-proxy

**Domain Lists**

![screenshot](media/dnscrypt-manager-lists_result.png)
![screenshot](media/dnscrypt-manager-lists2_result.png)

- Allowed Domains, shown by default - this is editable by hand, and a Save List button and Restart proxy button are there to update allowed-names.txt after edit
- Blocked Domains, shows the first 300 lines of blocked-names.txt (theres a hard filesize limit on text controls, and my personal blocked-names.txt file is 35Mb). Beyond 300 lines, you can search for, and remove domains
- Reload, reloads allowed and Blocked lists

**Blocklist**

![screenshot](media/dnscrypt-manager-blocklist1_result.png)![screenshot](media/dnscrypt-manager-blocklist2_result.png)![screenshot](media/dnscrypt-manager-blocklist3_result.png)

- List of curated common blocklist sources (can check sizes of lists and total size before building - note: not all sources will report their filesize)
- Ability to add your own blocklist sources
- Ability to add your own blocked domains via local-additions
- Build blocklist
- Schedule build of blocklist

**Settings**

![screenshot](media/dsncrypt-manager-settings_result.png)

- Dnscrypt Directory - must point to where your dnscrypt-proxy blocked and allow files are - by default (and using my own dnscrypt-proxy magisk module)) this is /storage/emulated/0/dnscrypt-proxy  (sdcard/dnscrypt-proxy) 
- Theme - change themes
- Button Style - change button style
- Save Settings - saves the settings


### Requirements: 

- A running dnscrypt-proxy service and setup
- blocked-names.txt and allowed-names.txt lists and blocked-names.log configured in the Dnscrypt Directory in app Settings
- `blocked-names.log`
- `allowed-names.txt`
- `blocked-names.txt`
- Root granted to Dnscrypt Monitor in your root manager app


### Magisk Module:

A full dnscrypt-proxy module including the Dnscrypt Manager app is available here:  https://github.com/therealadrianmmiller/AndroidDnsCryptProxyModule
