# Cassiopeia Watch

Cassiopeia Watch is an unofficial, open-source Android app for Casio G-Shock, Edifice and Pro Trek Bluetooth watches. It connects to the watch over Bluetooth Low Energy without a Casio account. It can set the watch time, edit watch alarms, send phone calendar events to the watch as reminders, change some watch settings, and let the watch buttons trigger actions on the phone.

This project is a derivative of [CasioGShockSmartSync](https://github.com/izivkov/CasioGShockSmartSync) by Ivo Zivkov. It is not affiliated with, endorsed by, or sponsored by Casio Computer Co., Ltd. "Casio", "G-Shock", "Edifice" and "Pro Trek" are trademarks of their respective owners and are used here only to describe compatibility.

Status: experimental prerelease. The first version is 40.10.

## Features

- No account or login. The app has no server of its own.
- Time: send the phone time, time zone and DST setting to the watch. Optionally use an NTP server as the time source, and apply a fine adjustment in milliseconds.
- Auto-sync on reconnect: when the watch reconnects, the app can sync it automatically.
- Alarms: read and edit the watch alarms, in a daily or a weekly view, and schedule the hourly signal (chime). Alarm names are stored in the watch's scratchpad memory.
- Events: send upcoming events from the phone calendar to the watch as reminders. This tab is shown only for watches that support reminders, and some recurrence rules are marked incompatible.
- Watch buttons: choose what a short press and a long press on the watch do. The options are sending a broadcast intent that automation apps such as Tasker or MacroDroid can receive, and ringing the phone ("Find my phone").
- Notifications: for watches that support app notifications, forward phone notifications to the watch (requires notification access).
- Watch settings: light, power saving, font, locale and similar options, on models that expose them.
- Multiple watches: more than one watch can be paired, each with its own sync history.
- Sync history: a local log of recent sync operations for each watch.
- Backup: export and import the app settings as a JSON file.
- The interface is translated into several languages (see `app/src/main/res/values-*`). Some newer strings are English only.

## Compatibility

The app talks to the watch through the [GShockAPI](https://github.com/izivkov/GShockAPI) library, so compatibility depends on that library and on the watch firmware.

Tested:

- Casio ABL-100WE, by the maintainer. This is the only model that has been tested with this app.

Expected to work, untested:

- Models listed as supported in the GShockAPI documentation: G(M)W-5600, G(M)W-5000, GA-B2100, GST-B500, MSG-B100, G-B001, GBD-800 (partial support), MRG-B5000, GCW-B5000.
- Models the library has code paths for but does not list in its documentation: DW-H5600, other DW models, GMW-BZ5000, GW-BX5600, GPR, GBD, EQB, ECB-10/20/30.

"Expected to work" means only that the library recognises the model name. It does not mean the features of this app have been checked on that model.

The app needs Android 8.0 (API 26) or newer, and a phone with Bluetooth Low Energy and companion device pairing support.

## Installation

Cassiopeia Watch is distributed only as an APK attached to a GitHub prerelease of this repository. It is not on Google Play or F-Droid. The package name is `com.beamburst.casswatch`.

1. Download the APK, the checksum and the signer fingerprint from the [releases page](https://github.com/cihyboj/cassiopeia-watch/releases).
2. Check the checksum and compare the output with the published value:
   ```
   sha256sum <apk file>
   ```
3. Check the signer with the Android SDK build tools and compare the SHA-256 certificate digest with the published fingerprint:
   ```
   apksigner verify --print-certs <apk file>
   ```
4. Allow your browser or file manager to install unknown apps, then open the APK.

Version 40.10 requires a clean install. If an earlier build of this app is installed, uninstall it first. Settings from earlier builds are not carried over.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12 and newer) | Find and talk to the watch. `BLUETOOTH_SCAN` is declared with `neverForLocation`. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older) | The same, on older Android versions. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` (fine location only up to Android 11) | Older Android versions require location permission to deliver Bluetooth scan results. The app does not read or store your location. |
| `REQUEST_COMPANION_PROFILE_WATCH`, `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` | Pair the watch through the Android Companion Device Manager and learn when it comes in range, so the app can connect in the background. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the connection service alive while the watch is connected. |
| `RECEIVE_BOOT_COMPLETED` | Restore background watch detection after the phone restarts. |
| `POST_NOTIFICATIONS` | Show the app's own notifications, such as the foreground service and find-my-phone notifications. |
| `READ_CALENDAR` | Read calendar events to send to the watch. Requested when you open the Events tab. |
| Notification access (a special access you grant in system settings) | Forward phone notifications to watches that support it. |
| `SCHEDULE_EXACT_ALARM`, `com.android.alarm.permission.SET_ALARM` | The best-effort phone alarm fallback described below. |
| `INTERNET` | Optional NTP time queries over UDP. The app makes no other network connections. |

The manifest also declares `ACCESS_NOTIFICATION_POLICY`, `WAKE_LOCK` and `WRITE_EXTERNAL_STORAGE` (Android 9 and older). No direct use of these was found in the app's own code.

## Known limitations

- This is an experimental prerelease, and only one watch model has been tested.
- The phone alarm fallback is best-effort. When the watch may not have received an alarm change, the app can schedule an alarm in the phone's clock app shortly before it should ring. This depends on the exact-alarm permission, on the clock app, and on the phone not restricting background work.
- Weekly alarms and one-time alarms that the phone manages are written to the watch when the app next connects to it. If the watch does not reconnect before the alarm is due, the watch does not have the change.
- Background connection behaviour varies between Android versions and manufacturers' battery management.
- Calendar events with some recurrence rules cannot be represented on the watch.

## Building from source

Requirements: JDK 21 and the Android SDK (compile SDK 36). If your environment does not provide the SDK location, create `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

```
./gradlew assembleDebug
./gradlew test lint
```

The app builds against a fork of the GShockAPI library, `com.github.cihyboj:GShockAPI`, pinned to commit `042cc1e2b5d4f5a325d70293dabe0497bc9d11e1` and resolved through [JitPack](https://jitpack.io). The fork (MIT licence, Copyright Ivo Zivkov) changes how the time is written to the watch: it removes an incorrect manual DST adjustment, writes the sub-second field of the time packet correctly, and adds a `primeTimeCache()` call. Release builds need signing credentials supplied through environment variables; see `app/build.gradle`.

## Privacy

The app has no accounts, analytics, advertising or server of its own. It keeps its data on your device and sends data to your watch over Bluetooth. The only network traffic is optional NTP time queries. See the [privacy policy](docs/index.html).

## Contributing and security

- Contributions: [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnerability reports: [SECURITY.md](SECURITY.md)
- Bugs and questions: [GitHub issues](https://github.com/cihyboj/cassiopeia-watch/issues)

## Licence

Cassiopeia Watch is licensed under the Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The same files are bundled in the app's assets.

## Acknowledgments

Cassiopeia Watch builds on [CasioGShockSmartSync](https://github.com/izivkov/CasioGShockSmartSync) and [GShockAPI](https://github.com/izivkov/GShockAPI) by Ivo Zivkov and their contributors, who worked out the watch protocol and wrote most of the code this app is based on. Thanks also to the authors of the open-source libraries listed in `THIRD_PARTY_NOTICES.md`.
