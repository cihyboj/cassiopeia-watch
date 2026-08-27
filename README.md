# Cassiopeia Watch

<p align="center">
  <img src="images/cassiopeia-watch.png" alt="Cassiopeia Watch logo" width="300">
</p>

[![GitHub](https://img.shields.io/badge/GitHub-Source-blue)](https://github.com/cihyboj/cassiopeia-watch)
[![Version](https://img.shields.io/badge/version-25.7-blue)](https://github.com/cihyboj/cassiopeia-watch/releases)
[![Connection Speed](https://img.shields.io/badge/Connection-Fast-green)](#)

**Cassiopeia Watch** is an unofficial, open-source Android app for G-Shock, Edifice, and Pro Trek watches. It allows you to control your watch, sync data, and configure settings **without a Casio ID**.

It offers a faster, privacy-focused, and more powerful alternative to the official Casio app.

Cassiopeia Watch is not affiliated with, endorsed by, or sponsored by Casio Computer Co., Ltd.

---

## Key Features

- **No Account Required**: Skip the Casio ID login entirely.
- **Calendar Sync**: Push your calendar events (Google, Samsung, etc.) to your watch as reminders.
- **Auto Timezone**: Automatically adjusts watch time when you travel.
- **Button Actions**: Use your watch buttons to find your phone or trigger automation apps on it.
- **Alarm Sync**: Configure watch alarms easily from your phone.
- **Phone Finder**: Trigger a loud alarm on your phone to find it.

---

## Supported Watch Models

The app works with many Bluetooth-enabled G-Shock, Edifice, and Pro Trek models.

<p align="center">
  <img src="images/gw_b5600.png" width="150" alt="GW-B5600" style="margin: 10px;">
  <img src="images/ga_b2100.png" width="150" alt="GA-B2100" style="margin: 10px;">
  <img src="images/dw-b5600.png" width="150" alt="DW-B5600" style="margin: 10px;">
  <img src="images/ecb_30d.png" width="150" alt="ECB-30" style="margin: 10px;">
</p>

| Series | Compatible Models (Examples) | Note |
|:---|:---|:---|
| **Square** | GW-B5600, GMW-B5000, GW-5000, DW-B5600 | Classic square design support |
| **CasiOak** | GA-B2100, GBM-2100 | Solar and Bluetooth variants |
| **G-Steel** | GST-B500, GST-B400, GST-B200 | Metal series |
| **Edifice** | ECB-10, ECB-20, ECB-30 | Bluetooth Edifice models |
| **Others** | GBD-800, GBD-H1000, GG-B100 (Mudmaster) | Step trackers and sensors |

> **Note**: While these watches connect, not all specific hardware features (like fitness tracking steps on GBD models) may be fully supported yet.

---

## App Walkthrough

Here is a guide to the main screens of the application.

### 1. Connection Screen

This is the first screen you see. It handles the Bluetooth connection to your watch.

- **Status**: Shows generic connection status.
- **Scan/Connect**: Automatically scans for your watch.
- **Paired Devices**: Lists your previously connected watches for quick access.

The app communicates directly with the watch via BLE. Connection is typically much faster than the official app (~3.5s vs 12s).

<br clear="all"/>

### 2. Time Setting

Manage your watch's timekeeping effortlessly.

- **Local Time**: Displays the current time from your phone.
- **Send to Watch**: Tap the button to sync your phone's time, timezone, and DST settings to the watch immediately.
- **Home Time**: Shows the 'Home' city configured on the watch.
- **Battery**: Displays the current battery level of the watch (e.g., High, Medium, Low).

<br clear="all"/>

### 3. Alarms

Read and configure the alarms stored on your watch.

- **View Alarms**: See the current settings for all 5 alarms and the hourly signal (Chime).
- **Edit**: Tap any alarm to change its time.
- **Sync**: You can send these alarm settings to the watch. 

*Note: Due to Android limitations, we cannot read alarms set in your phone's native Clock app, but we can set the watch's internal alarms.*

<br clear="all"/>

### 4. Events (Calendar Sync)

Sync your Android calendars to your watch!

- **Upcoming Events**: Lists future events from your phone's calendars.
- **Send to Watch**: Pushes these events to the watch's Reminder feature.
- **Compatibility**: Supports standard Android calendar events. Complex recurrence rules might show as "Incompatible".

The watch will display these as reminders with the date and title.

<br clear="all"/>

### 5. Button Actions

Assign actions to button presses on the watch.

- **Find Phone**: Make your phone ring loudly so you can locate it.
- **Automation Broadcasts**: Short and long presses can send configurable broadcasts to your phone, so automation apps (e.g. Tasker) can react to your watch buttons.
- **How to use**: Press the lower-right button on the watch (when in Time mode) to trigger the configured action.

<br clear="all"/>

### 6. Settings

Configure app and watch preferences.

- **Watch Settings**: Adjust specific watch configurations (light duration, power saving, etc.).
- **App Settings**: Configure theme, behavior, and advanced options.

<br clear="all"/>

---

## Installation

- **GitHub**: [Download APK from Releases](https://github.com/cihyboj/cassiopeia-watch/releases/)

Cassiopeia Watch is not currently published on F-Droid; the GitHub release above is the only official install source for the `com.beamburst.casswatch` package.

## Contributing

We welcome contributions!
- **Code**: Submit a PR to add features or fix bugs.
- **Watches**: If you have a G-Shock model not yet supported, functionality can often be added. Donations of test units are also highly appreciated to help expand compatibility.

## Related Projects

| Project | Description |
|:---|:---|
| [**GShockAPI**](https://github.com/cihyboj/GShockAPI) | The core Android library that **this app is built on** (a maintained fork of the upstream GShockAPI library). It encapsulates all low-level Bluetooth communication and watch protocol logic (time sync, alarms, reminders, DST, etc.) into a clean Kotlin API. This project consumes it as a direct dependency via `implementation(libs.gshockapi)`. |

## Attribution

Cassiopeia Watch is a fork of the upstream [CasioGShockSmartSync](https://github.com/izivkov/CasioGShockSmartSync) project by Ivo Zivkov. See `NOTICE` for attribution details.

---

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.
