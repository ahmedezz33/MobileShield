# MobileShield — درع الجوال

An on-device Android firewall, privacy monitor and data shredder that needs **no root**.
Arabic and English interface.

جدار حماية ومراقبة خصوصية وإتلاف بيانات لأندرويد، يعمل **بدون روت**، بواجهة عربية وإنجليزية.

## Features

- **Firewall** — every connection on the phone passes through a local VPN (no remote server). Per-app blocking on Wi-Fi, mobile data or while locked; network-wide kill switches; mobile data allowed per generation (2G/3G/4G/5G).
- **Ad and tracker blocking** — DNS lists (AdGuard, StevenBlack, Peter Lowe); blocked lookups are attributed to the exact app through per-domain sinkhole addresses.
- **Anti-bypass** — direct-to-IP connections (learn or block mode), DNS-over-HTTPS/TLS blocking, Private DNS locked off.
- **Monitoring** — instant alerts with the app's name whenever the microphone or camera turns on, calls (incl. VoLTE / Wi-Fi calling), SMS, data usage from system counters, hotspot, cell towers, usage history, last sensitive-permission access, notification log, system log.
- **Integrity watchdog** — verified boot, bootloader lock, Knox warranty bit, SELinux and root files checked every minute.
- **Files** — shredder (files, folders, thumbnails, recycle bin, free space), deep clean (all-app cache, leftovers of removed apps, temp and log files) and recovery (recycle bin, thumbnails, copies inside messaging apps).

Details, setup and honest limits: [MobileShield/README.md](MobileShield/README.md) (Arabic).

## Install

A prebuilt APK is at [MobileShield/MobileShield.apk](MobileShield/MobileShield.apk). Some features need one-time grants over USB; `MobileShield/build-apk.ps1 -Install` builds, installs and grants them.

## Build

Kotlin + Jetpack Compose, native engine in C (NDK 26.3, CMake 3.22), `minSdk 29`, `targetSdk 34`, JDK 17, Gradle 8.7.

```
powershell -ExecutionPolicy Bypass -File MobileShield/build-apk.ps1
```

## Contact — التواصل

- **Bugs and feature requests / الأخطاء والاقتراحات:** open an [issue](https://github.com/ahmedezz33/MobileShield/issues).
- **Security vulnerabilities / الثغرات الأمنية:** please report privately through [Report a vulnerability](https://github.com/ahmedezz33/MobileShield/security/advisories/new) — only the maintainer sees it. See [SECURITY.md](SECURITY.md).

## License

GPL-3.0 — see [LICENSE](LICENSE).

The packet engine in `MobileShield/app/src/main/cpp/netguard/` is from
[NetGuard](https://github.com/M66B/NetGuard), Copyright © 2015–2026 Marcel Bokhorst,
licensed under GPLv3, with modifications: JNI bindings renamed, the app uid passed to the
DNS callbacks, uid lookup through ConnectivityManager for TLS server names, and per-domain
sinkhole answers for blocked names.
