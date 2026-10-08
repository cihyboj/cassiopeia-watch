# Cassiopeia Watch 40.10 (experimental prerelease)

This is the first public release of Cassiopeia Watch, an unofficial open-source app for Casio G-Shock, Edifice and Pro Trek Bluetooth watches. It is a derivative of CasioGShockSmartSync by Ivo Zivkov and is not affiliated with Casio.

## Status

Experimental. Only the Casio ABL-100WE has been tested, by the maintainer. Other models that the GShockAPI library recognises may work but are untested.

## Clean install required

This version is signed with a new key and uses a new storage layout. Uninstall any earlier build of the app before installing. Settings from earlier builds are not carried over.

## What works

- Pairing a watch and connecting in the background
- Sending time, time zone and DST to the watch, with an optional NTP time source and fine adjustment
- Automatic sync when the watch reconnects
- Reading and editing watch alarms and the hourly signal
- Sending calendar events to the watch as reminders, on watches that support reminders
- Short and long watch button actions: broadcast intents for automation apps, and find my phone
- Forwarding phone notifications on watches that support it
- More than one paired watch, sync history, and export/import of app settings

## Known limitations

- The phone alarm fallback for watch alarms is best-effort.
- Weekly and one-time alarms managed by the phone are written to the watch when the app next connects, so they depend on the watch reconnecting in time.
- Some calendar recurrence rules cannot be represented on the watch.
- Background connection behaviour differs between Android versions and manufacturers.
- Some strings in Settings are English only.

## Verifying the download

Compare the APK's SHA-256 checksum and signing certificate fingerprint with the values published with this release. Licence notices are in the repository and bundled in the app.

## Privacy

No accounts, analytics or advertising. The only network traffic is optional NTP time queries. See the privacy policy in `docs/index.html`.

Report problems at https://github.com/cihyboj/cassiopeia-watch/issues. Report security problems privately, see `SECURITY.md`.
