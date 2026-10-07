<div align="center">

<img src="picture/icon_source/icon.png" alt="ClassFlow" width="128" />

# ClassFlow

**A lightweight timetable app for WBUer · Wuhan Business University Edition**

[![Release](https://img.shields.io/badge/release-v1.1.2-ff4d8d?style=flat-square)](https://gitee.com/hjwqa/class-flow/releases)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](https://gitee.com/hjwqa/class-flow/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7f52ff?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache--2.0-2e7d32?style=flat-square)](LICENSE)

[Download](#download) · [Features](#features) · [Screenshots](#screenshots) · [FAQ](#faq) · [Build](#build--development) · [中文文档](README.md)

</div>

**ClassFlow** is a lightweight, open-source Android timetable app built with Jetpack Compose and tailored for **Wuhan Business University (WBU)**. It syncs your timetable straight from the academic system, bundles the campus utilities students actually use, and lets you make the schedule yours.

> ⚠️ **WBU only.** ClassFlow is a derivative work of [shiguangschedule (拾光课程表)](https://github.com/XingHeYuZhuan/shiguangschedule) and only adapts WBU's academic system and campus services. If you are from another school, please use the upstream project.

## Features

### 📚 Timetable

- Weekly and Today views, swipe left/right to change weeks, bottom dock for quick navigation
- Multiple timetables per semester, bound to a student ID, with lock & archive to prevent accidental overwrites
- Long-press a course to fine-tune it: drag the endpoints to resize, drag the card to move, drop at the screen edge to move across weeks
- Conflicting courses are laid out side by side or truncated (toggleable); dashed dividers for stacked courses
- 24-hour timeline mode, hide period times, hide dates, show courses from other weeks
- Export to ICS / WakeUp / a high-resolution image, or sync to the system calendar

### 🔐 Academic system sync

- One-tap login & sync: probes the campus network and routes through direct access or WebVPN automatically
- One unified sign-in sheet for everything: password + captcha, SMS second factor, slider captcha, QR code, and certificate warnings
- Direct WebVPN portal login with a 9-digit student ID or 8-digit staff ID (other formats are resolved automatically)
- Pick any available semester, multi-campus time-slot templates, real student-ID extraction and schedule metadata rewriting

### 🏫 Campus services

| Service | Description |
| --- | --- |
| Grades | Grades and GPA by semester |
| Empty classrooms | Filter free rooms by time / building |
| Academic progress | Degree completion and course-category progress |
| Library | Search the catalogue, view loans, renew books |
| Campus card | Balance, transactions, utility payments |
| Payment code | Native campus-code screen: code on entry, auto-rotation, balance, screen kept on at full brightness; QR, CODE128 barcode and a digits view, with an official-page fallback |
| Scan | Let your phone complete the unified-auth QR login for a PC (status 2 → 1) and route Ujing device codes / shower controllers / generic link nodes; ZXing-C++ / ZXing engines (zxing-cpp by default), gallery import supported |
| Ujing devices | Jump straight to water dispensers, hair dryers, washers and dryers from their QR codes |
| Desktop shortcuts | Long-press the app icon for "Scan", "Campus Card" and "Payment Code" |

### 👤 Accounts & credentials

- Credentials grouped by service (Unified Auth / Academic / Library / WebVPN / Campus Card / WebDAV), with optional session verification on entry
- Stored per **service × account** (multi-account ready); legacy flat data is migrated safely
- Real server-side logout when clearing a session (CAS revokes `CASTGC`, JWXT invalidates `jw_uf`, OPAC destroys `PHPSESSID`, WebVPN logs out `TWFID`)
- Advanced mode (tap the title 3 times) reveals session credentials such as `TWFID` and `jw_uf`
- Failed logins roll back through a session snapshot and never wipe existing credentials

### 🎨 Personalization & widgets

- Glassmorphism course blocks with four presets (angular / clear / frost / liquid), adjustable blur and scrim
- Custom wallpaper with pinch-to-zoom and drag-to-pan
- Font styles (default / square / rounded / serif); Sakura palette that shifts by morning, afternoon and evening; light & dark themes
- Native home-screen widgets: Today 4x3 / 4x2, Compact 2x2, Small 2x1, Upcoming 4x2
- Simplified Chinese / Traditional Chinese / English

### 🔄 Updates

- In-app self-update through `PackageInstaller` sessions: silent on Android 12+ when eligible, system confirmation otherwise; missing "install unknown apps" permission is requested and the update retried on return; session errors fall back to the system installer
- Silent update check at launch (toggleable), skip a version, or point the app at your own update server

## Screenshots

<div align="center">

| Weekly timetable | Personalization | Home-screen widgets |
| :---: | :---: | :---: |
| <img src="picture/classflow-week.webp" width="240" alt="Weekly timetable (real device, Sakura morning palette)" /> | <img src="picture/Screenshot_2.png" width="240" alt="Personalization" /> | <img src="picture/Screenshot_3.png" width="240" alt="Widgets" /> |

</div>

## Download

| Channel | Link | Notes |
| --- | --- | --- |
| **Gitee Releases** (recommended) | <https://gitee.com/hjwqa/class-flow/releases> | Fast in mainland China |
| GitHub | <https://github.com/shiro123444/ClassFlow> | Source, issues, pull requests |

APKs are split per CPU architecture — install the one that matches your device:

| ABI | Devices | Direct link |
| --- | --- | --- |
| `arm64-v8a` | Most phones released after 2017 (**recommended**) | [app-prod-arm64-v8a-release.apk](https://gitee.com/hjwqa/class-flow/releases/download/v1.1.2/app-prod-arm64-v8a-release.apk) |
| `armeabi-v7a` | Older 32-bit devices | [app-prod-armeabi-v7a-release.apk](https://gitee.com/hjwqa/class-flow/releases/download/v1.1.2/app-prod-armeabi-v7a-release.apk) |
| `x86_64` | Emulators and some tablets | [app-prod-x86_64-release.apk](https://gitee.com/hjwqa/class-flow/releases/download/v1.1.2/app-prod-x86_64-release.apk) |

- Requires Android 8.0 (API 26) or newer; each APK is roughly 11–14 MB
- Allow "install unknown apps" the first time; updates are then handled inside the app
- The `dev` flavor uses the package name `com.shiro.classflow.dev` and can coexist with the release build

## Getting started

1. **Install and open** — follow the onboarding and set the semester start date (week numbers are wrong without it)
2. **Log in and sync** — tap "Sign-in sync" on the timetable screen (or "Me → Accounts & credentials"), sign in through direct access or WebVPN, and import the current semester
3. **Make it yours** — "Me → Personalization" for glass presets, wallpaper and fonts, or add a home-screen widget

## FAQ

<details>
<summary><b>Does it work outside Wuhan Business University?</b></summary>

No. Only WBU's academic system and campus services are adapted. Other schools should use [shiguangschedule](https://github.com/XingHeYuZhuan/shiguangschedule).
</details>

<details>
<summary><b>Are my credentials uploaded anywhere?</b></summary>

No. Credentials are stored only in the app's private storage on your device and sent directly to your school's own servers. The update check only fetches version information; it can be disabled or repointed at your own server.
</details>

<details>
<summary><b>The installer is blocked — what should I do?</b></summary>

Grant "install unknown apps" to the browser or file manager you are using (Settings → Apps → Special app access → Install unknown apps). When the in-app updater lacks this permission it prompts you and retries automatically once you return.
</details>

<details>
<summary><b>Tablets and emulators?</b></summary>

Supported — the UI adapts to landscape and tablet sizes; emulators should install the `x86_64` build. There is no iOS version.
</details>

<details>
<summary><b>How do I report a problem?</b></summary>

Open an [issue on GitHub](https://github.com/shiro123444/ClassFlow/issues) or join the QQ group **1050669511**.
</details>

## Tech stack

| Area | Technology |
| --- | --- |
| Language / build | Kotlin 2.4, Gradle 9 (Kotlin DSL + version catalog), JDK 21, AGP 9 |
| UI | Jetpack Compose + Material 3, Navigation 3, Coil, Haze (glass blur), native widget rendering |
| Architecture | MVVM with Hilt, Coroutines / Flow |
| Data | Room, Proto DataStore, kotlinx.serialization, Wire (protobuf) |
| Networking | OkHttp, Ktor, Jsoup (academic / campus page parsing) |
| Hardware | CameraX, ZXing-C++ / ZXing, WorkManager |
| Other | JGit, AppCompat DayNight, Apache-2.0 license-compliance plugin |

## Build & development

**Requirements:** JDK 21, Android SDK (`compileSdk = 37`), Git.

```bash
git clone https://github.com/shiro123444/ClassFlow.git
cd ClassFlow
```

Add the following to `local.properties` (already git-ignored):

```properties
# Ujing NFC / deep-link host: required — the build fails without it
CLASSFLOW_UJING_NFC_HOST=ujing.example.edu.cn
# Optional: generic link node (/url/, /u/) hub host; falls back to the Ujing host above
CLASSFLOW_LINK_HUB_HOST=hub.example.com
# Optional: in-app update API endpoint
CLASSFLOW_UPDATE_API_URL=https://example.com/classflow/update
# Optional: local JDK
org.gradle.java.home=C:\\path\\to\\jdk-21
```

The same values can be supplied as Gradle properties (`-PCLASSFLOW_UJING_NFC_HOST=...`) or environment variables; precedence is Gradle property > `local.properties` > environment variable.

```bash
./gradlew :app:testDevDebugUnitTest   # unit tests
./gradlew :app:assembleDevDebug       # dev flavor
./gradlew :app:assembleProdRelease    # release flavor (split per ABI)
```

- Release signing is injected via `-Pandroid.injected.signing.*`; local release builds fall back to the debug key and are for testing only
- CI: [`android-build.yml`](.github/workflows/android-build.yml) (manual build + signing) and [`android-release.yml`](.github/workflows/android-release.yml) (publishes a release from a build artifact)

## Contributing

Issues and pull requests are welcome at <https://github.com/shiro123444/ClassFlow>. Development happens on the `dev` branch, and commit messages follow [Conventional Commits](https://www.conventionalcommits.org/). The intentional differences from upstream are documented in [`CLASSFLOW_CUSTOMIZATIONS.md`](CLASSFLOW_CUSTOMIZATIONS.md).

## Disclaimer

ClassFlow is an unofficial, student-made project and is **not affiliated with Wuhan Business University**. Campus endpoints are based on publicly reachable pages and may break whenever the school updates its systems. Please respect your school's policies and avoid hammering their servers. Use at your own risk.

## License & credits

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE). The full third-party license list ships with the app (`app/src/main/assets/open_source_licenses.html`).

- [shiguangschedule (拾光课程表)](https://github.com/XingHeYuZhuan/shiguangschedule) — the upstream project ClassFlow derives from
- [Jetpack Compose](https://developer.android.com/jetpack/compose), [Kotlin](https://kotlinlang.org), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/), [Haze](https://github.com/chrisbanes/haze) and the wider open-source community
- Everyone who tested the app and sent feedback 🫶

<div align="center">

**If ClassFlow helped you, consider giving it a ⭐️**

[⬆ Back to top](#classflow) · [中文文档](README.md)

</div>
