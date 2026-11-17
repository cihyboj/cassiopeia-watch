# Cassiopeia Watch 40.10 (experimental prerelease)

This is the first public release of Cassiopeia Watch, an unofficial open-source app built and tested for the Casio ABL-100WE Bluetooth watch. It is a derivative of CasioGShockSmartSync by Ivo Zivkov and is not affiliated with Casio.

## Status

Experimental. Only the Casio ABL-100WE has been tested, by the maintainer. Other models that the GShockAPI library recognises may work but are untested.

## What works

- Pairing a watch and connecting in the background
- Sending time, time zone and DST to the watch, with an optional NTP time source and fine adjustment
- Automatic sync when the watch reconnects
- Reading and editing watch alarms and the hourly signal
- Sending calendar events to the watch as reminders, on watches that support reminders
- Watch button actions: broadcast intents for automation apps on short and long press, and find my phone on long press
- More than one paired watch, sync history, and export/import of app settings

## Known limitations

Alarms:

- The watch stores every alarm as a daily alarm. A one-time alarm keeps ringing every day after its date, and a weekly alarm can ring on excluded days, until the app connects to the watch again and updates it, or you turn the alarm off on the watch.
- The phone alarm fallback is off by default. Turn on "Phone alarm backup" on the Alarms screen to use it. It is best-effort: it hands the alarm to the phone's clock app with a time but no date, cannot confirm that the clock app created it, and cannot cancel or remove it afterwards, so these alarms stay in the clock app until you delete them.
- The fallback needs the exact-alarm permission. Turning the switch on asks for it; if it is denied, the fallback does not run.
- After the phone restarts, the fallback does not work until the phone has been unlocked once.

Syncing:

- Alarm writes are confirmed by reading the alarms back from the watch. Time, settings, timer and reminder writes are not confirmed: the app reports them as sent, not as received.
- Changes made directly on the watch are not merged into what the app has saved. The next sync can overwrite them.
- With more than one paired watch, the app refuses to send changes when the selected watch is not the connected one.
- After the three-minute inactivity disconnect the phone may reconnect to the watch straight away.
- Time accuracy below one second depends on the watch firmware and Bluetooth timing.

Other:

- Find my phone depends on the phone's sound and Do Not Disturb settings. The app asks for notification permission so it can show a Stop button; without it, press the watch button again to stop the ringing, or it stops after 30 seconds.
- Some calendar recurrence rules cannot be represented on the watch.
- Only one app can hold the watch's Bluetooth connection. Do not run Casio's app or another G-Shock app for the same watch at the same time.
- Background connection behaviour differs between Android versions and manufacturers.
- Some strings in Settings are English only.
- Forwarding phone notifications to the watch is not included. It needs notification access, which makes Google Play Protect block installing the APK from a browser or file manager, and the tested watch does not support it.

## Verifying the download

Check `cassiopeia-watch-<version>.apk` against `cassiopeia-watch-<version>.apk.sha256`, and compare the signing certificate digest with the one published in the README (`D63C4B494FF1B1867D8A245DFA54F888C87BEED04E63ED40CB32683ADFCB4707`). Licence notices are in the repository and bundled in the app.

## Privacy

No accounts, analytics or advertising, and the developers receive nothing automatically. The only network traffic is optional NTP time queries. See the privacy policy in `docs/index.html`.

Report problems at https://github.com/cihyboj/cassiopeia-watch/issues. Report security problems privately, see `SECURITY.md`.
