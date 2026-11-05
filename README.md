# MobileShield — درع الجوال

**[العربية](#العربية) · [English](#english)**

---

<div dir="rtl">

## العربية

**درع الجوال** جدار حماية ومراقبة للخصوصية وإتلاف للبيانات لجوالات أندرويد. يعمل **بدون روت**، وبواجهة عربية وإنجليزية.

كل اتصال يخرج من الجوال يمر أولًا على درع الجوال، فيقرر هل **يسمح** به أو **يمنعه**، ويسجّله لتراه. يحدث كل هذا **داخل الجوال فقط**، دون أي خادم خارجي، ولا يُرسل أي شيء عنك إلى أي جهة.

### المزايا

| التبويب | ما يقدّمه |
|---|---|
| **التطبيقات** | منع أي تطبيق من الإنترنت على الواي فاي، أو بيانات الجوال، أو عند قفل الشاشة، مع عرض استهلاك كل تطبيق |
| **السجل** | كل اتصال وطلب DNS: التطبيق، والموقع، والوقت، والنتيجة. يحفظ **آخر 7 أيام**، وفيه زر «حفظ السجلات» |
| **المراقبة** | تنبيه فوري باسم أي تطبيق يشغّل **المايكروفون أو الكاميرا**، مع زر «إيقاف التطبيق». ويعرض أيضًا المكالمات (ومنها VoLTE)، والرسائل، واستهلاك البيانات، وسجل الاستخدام، وسجل الإشعارات، وأبراج الاتصال |
| **التحكم** | قطع الإنترنت كليًا، والسماح بالبيانات حسب الجيل (2G وحتى 5G)، وإيقاف الاتصالات **حسب الخدمة** (Microsoft وGoogle وسامسونج وغيرها) بخيارين: حظر بيانات الاستخدام فقط، أو قطع كامل بعد التأكيد |
| **الملفات** | **الإتلاف** النهائي للملفات والمصغّرات وسلة المحذوفات والمساحة الفارغة، و**التنظيف العميق** للكاش وبقايا التطبيقات المحذوفة، و**الاسترجاع** من سلة المحذوفات والمصغّرات ونسخ تطبيقات المراسلة |
| **الإعدادات** | **فحص سلامة النظام كل دقيقة** (التمهيد الموثَّق وقفل الإقلاع وKnox وSELinux وملفات الروت)، وحجب الإعلانات، والحماية من هجمات «بدون نقرة»، واختيار خادم DNS |

### ما يحجبه
- **الإعلانات والمتتبعات** بقوائم AdGuard وStevenBlack وPeter Lowe، مع معرفة **التطبيق الدقيق** الذي طلب كل إعلان.
- **محاولات تجاوز الفلتر:** DNS المشفّر (DoH وDoT)، والاتصال المباشر بعناوين IP.
- **بيانات الاستخدام** التي ترسلها Microsoft وGoogle وسامسونج وغيرها.

### التثبيت
1. حمّل ملف التطبيق من **[صفحة الإصدارات](https://github.com/ahmedezz33/MobileShield/releases/latest)**، أو من [MobileShield/MobileShield.apk](MobileShield/MobileShield.apk).
2. افتحه على الجوال ووافق على التثبيت.
3. بعض المزايا تحتاج صلاحيات تُمنح **مرة واحدة** عبر الكابل من الكمبيوتر. التفاصيل في [الدليل](MobileShield/README.md).
4. على الجوال فعّل: «VPN دائم التشغيل»، و«حظر الاتصالات بدون VPN»، واجعل البطارية «غير مقيّد».

### ما لا يستطيعه أي تطبيق بدون روت (بصراحة)
- إطفاء الواي فاي أو البلوتوث أو المايكروفون أو الكاميرا مباشرة من داخل التطبيق. لذلك يفتح التطبيق الصفحة المناسبة وتطفئها أنت بلمسة.
- حظر المكالمات العادية والرسائل SMS. التطبيق يراقبها فقط.
- استرجاع ما حُذف نهائيًا. الذاكرة مشفّرة وتُفرَّغ بعد الحذف.
- منع هجمات «بدون نقرة» كليًا. الحماية الحقيقية منها هي التحديثات و«الحماية المتقدمة» أو «Auto Blocker».

### التواصل
- **الأخطاء والاقتراحات:** افتح [Issue](https://github.com/ahmedezz33/MobileShield/issues).
- **الثغرات الأمنية:** أبلغ عنها بشكل خاص من [هنا](https://github.com/ahmedezz33/MobileShield/security/advisories/new)، ولن يراها غير صاحب المشروع. انظر [SECURITY.md](SECURITY.md).

### الرخصة
GPL-3.0. محرك الحزم مأخوذ من مشروع [NetGuard](https://github.com/M66B/NetGuard) (© 2015–2026 Marcel Bokhorst) مع تعديلات.

</div>

---

## English

An on-device Android firewall, privacy monitor and data shredder that needs **no root**.
Arabic and English interface. Every connection passes through a local VPN on the phone itself — no remote server, nothing about you is sent anywhere.

### Features

- **Firewall** — per-app blocking on Wi-Fi, mobile data or while locked; network-wide kill switches; mobile data allowed per generation (2G/3G/4G/5G).
- **Ad and tracker blocking** — DNS lists (AdGuard, StevenBlack, Peter Lowe); blocked lookups are attributed to the exact app through per-domain sinkhole addresses.
- **Per-service privacy switches** — block only the usage data, or cut the app off (with confirmation), for Microsoft, Google, Samsung, Hiya, Claude and more.
- **Anti-bypass** — direct-to-IP connections (learn or block mode), DNS-over-HTTPS/TLS blocking, Private DNS locked off.
- **Monitoring** — instant alerts naming the app whenever the microphone or camera turns on, calls (incl. VoLTE / Wi-Fi calling), SMS, data usage, hotspot, cell towers, usage history, last sensitive-permission access, notification log, system log.
- **7-day history** with per-day browsing and a *Save logs* export (ZIP of CSV files).
- **Integrity watchdog** — verified boot, bootloader lock, Knox warranty bit, SELinux and root files checked every minute.
- **Files** — shredder, deep clean and recovery.

Details, setup and honest limits: [MobileShield/README.md](MobileShield/README.md).

### Install

Download the APK from the **[latest release](https://github.com/ahmedezz33/MobileShield/releases/latest)**. Some features need one-time grants over USB; `MobileShield/build-apk.ps1 -Install` builds, installs and grants them.

### Build

Kotlin + Jetpack Compose, native engine in C (NDK 26.3, CMake 3.22), `minSdk 29`, `targetSdk 34`, JDK 17, Gradle 8.7.

```
powershell -ExecutionPolicy Bypass -File MobileShield/build-apk.ps1
```

### Contact

- **Bugs and feature requests:** open an [issue](https://github.com/ahmedezz33/MobileShield/issues).
- **Security vulnerabilities:** report privately through [Report a vulnerability](https://github.com/ahmedezz33/MobileShield/security/advisories/new). See [SECURITY.md](SECURITY.md).

### License

GPL-3.0 — see [LICENSE](LICENSE).

The packet engine in `MobileShield/app/src/main/cpp/netguard/` is from
[NetGuard](https://github.com/M66B/NetGuard), Copyright © 2015–2026 Marcel Bokhorst,
licensed under GPLv3, with modifications: JNI bindings renamed, the app uid passed to the
DNS callbacks, uid lookup through ConnectivityManager for TLS server names, per-domain
sinkhole answers for blocked names, and an immediate reset for refused connections.
