# Cassiopeia Watch 40.10 (experimental prerelease)

This is the first public release of Cassiopeia Watch, an unofficial open-source app for Casio G-Shock, Edifice and Pro Trek Bluetooth watches. It is a derivative of CasioGShockSmartSync by Ivo Zivkov and is not affiliated with Casio.

## Status

Experimental. Only the Casio ABL-100WE has been tested, by the maintainer. Other models that the GShockAPI library recognises may work but are untested.

## Installing over an earlier build

This version is signed with a different key than earlier builds, so Android cannot update an existing install in place. In the old build, open Settings and export a backup. Uninstall the old build, install this APK, import the backup in Settings, and pair the watch again.

## What works

- Pairing a watch and connecting in the background
- Sending time, time zone and DST to the watch, with an optional NTP time source and fine adjustment
- Automatic sync when the watch reconnects
- Reading and editing watch alarms and the hourly signal
- Sending calendar events to the watch as reminders, on watches that support reminders
- Watch button actions: broadcast intents for automation apps on short and long press, and find my phone on long press
- Forwarding phone notifications on watches that support it
- More than one paired watch, sync history, and export/import of app settings

## Known limitations

- The phone alarm fallback for watch alarms is best-effort.
- Weekly and one-time alarms managed by the phone are written to the watch when the app next connects, so they depend on the watch reconnecting in time.
- Some calendar recurrence rules cannot be represented on the watch.
- Background connection behaviour differs between Android versions and manufacturers.
- Some strings in Settings are English only.

## Verifying the download

Check `cassiopeia-watch-<version>.apk` against `cassiopeia-watch-<version>.apk.sha256`, and compare the signing certificate digest with `signer-sha256.txt`. Licence notices are in the repository and bundled in the app.

## Privacy

No accounts, analytics or advertising, and the developers receive nothing automatically. The only network traffic is optional NTP time queries. See the privacy policy in `docs/index.html`.

Report problems at https://github.com/cihyboj/cassiopeia-watch/issues. Report security problems privately, see `SECURITY.md`.
