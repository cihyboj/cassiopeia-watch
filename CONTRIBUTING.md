# Contributing

Cassiopeia Watch is a small project maintained by one person. Bug reports, test results from other watch models and focused pull requests are welcome. For anything large, open an issue first so we can agree on the approach.

## Building and testing

Requirements: JDK 21 and the Android SDK (compile SDK 36).

```
./gradlew assembleDebug
./gradlew test lint
```

Both `test` and `lint` should pass before you open a pull request. Existing lint issues are recorded in `app/lint-baseline.xml`; new lint errors fail the build.

The app builds against a pinned fork of GShockAPI resolved through JitPack, see the README. In production code, all communication with the watch goes through `GShockRepository`; the app never talks to BLE directly. The one deliberate exception is the BLE debug panel, which exists only in debug builds and uses the library's lower-level API directly.

## Code conventions

See `CLAUDE.md` for the architecture overview. In short:

- Kotlin, Jetpack Compose, Hilt, one ViewModel per screen with state exposed as `StateFlow` and one-time events as `SharedFlow`.
- Use `Timber` for logging, `AppSnackbar` for user feedback, and `WatchInfo` capability flags (for example `WatchInfo.hasReminders`) to show features only on watches that support them.
- Inject the application context, not an activity context, into singletons and ViewModels.
- Do not hardcode user-visible strings. Add them to `app/src/main/res/values/strings.xml` and add translations to every `values-*` locale folder. A string that is deliberately English only is marked `translatable="false"`.
- Do not add new network use, analytics or other data collection. The privacy policy in `docs/index.html` must stay accurate; if a change affects it, update it in the same pull request.

## Testing on a real watch

BLE behaviour differs between watch models, firmware versions and phones, and cannot be fully covered by unit tests. If you change connection, time, alarm, event or button-action code, test it on a real watch and say in the pull request which model, firmware and Android version you used. If you cannot test a model, say so. Do not post real Bluetooth addresses in issues or pull requests.

## Pull requests

- Keep each pull request focused on one change, with a clear description of what and why.
- Add or update unit tests where practical.
- Do not include signing keys, passwords or personal data.
- By contributing you agree that your contribution is licensed under the Apache License 2.0, the licence of this project.
- Files that come from CasioGShockSmartSync keep their original headers. Do not remove them.
