# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Cassiopeia Watch** is an unofficial open-source Android app (package `com.beamburst.casswatch`) that controls Casio Bluetooth watches without requiring a Casio account. Its target and only tested device is the Casio ABL-100WE; other watches that GShockAPI supports may work but are untested. It syncs time, calendar events, and alarms, and lets watch buttons trigger phone actions (find phone, automation broadcasts).

The core BLE protocol is entirely encapsulated in an external library, **GShockAPI** (`com.github.cihyboj:GShockAPI` via JitPack — a maintained fork of the upstream library, pinned by commit in `gradle/libs.versions.toml`). This app never talks to BLE directly — all watch communication goes through `GShockRepository`, which delegates to `GShockAPI` via Kotlin's `by` delegation. The library's Java package remains `org.avmedia.gshockapi`.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD env vars)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.beamburst.casswatch.scratchpad.AlarmNameStorageTest"

# Lint check (gated on app/lint-baseline.xml; new lint errors fail the build)
./gradlew lint

# Release to GitHub (uses gh CLI)
./release.sh 41.0     # opens a release PR (versionCode = major*1000 + minor); tagging the merge builds a draft prerelease
```

CI (GitHub Actions, `.github/workflows/ci.yml`) runs `test`, `lint`, `assembleDebug`, and `assembleRelease` (unsigned, to exercise R8) on pushes to `main` and on pull requests. Tag pushes trigger `.github/workflows/build-apk.yml`, which builds, verifies the signature, and uploads the signed APK to a GitHub release.

## Architecture

### Dependency Injection

Hilt is used throughout. The DI graph has two key bindings:

- `ApiModule` provides a singleton `GShockAPI(context)`
- `RepositoryModule` provides `GShockRepository` (wraps `GShockAPI` via `by api` delegation) and also binds `IGShockAPI` to the repository

ViewModels inject `GShockRepository` directly and call API methods on it. Components without Hilt injection (BroadcastReceivers, system services) use `@EntryPoint` interfaces via `EntryPointAccessors`.

### App State Machine

`GShockApplication` (the `Application` class) owns app-wide screen state via a `currentScreen: AppScreen` mutable state:

```
INITIAL → CONTENT_SELECTOR → MAIN_NAVIGATION
```

`AppContainer` renders a single `BottomNavigationBarWithPermissions` call site for all states (so transitions don't rebuild the nav host); `CONTENT_SELECTOR` may show a `CoverScreen` overlay for always-connected watches. `MainEventHandler` subscribes to `ProgressEvents` (the pub/sub bus from GShockAPI) and drives transitions by calling `IScreenManager` methods on `GShockApplication`.

### Event System

`ProgressEvents` (from GShockAPI) is a global reactive event bus. Code subscribes using:

```kotlin
ProgressEvents.runEventActions(subscriberId, arrayOf(
    EventAction("EventName") { /* handler */ }
))
```

Conventions:
- **Subscriber IDs**: singletons use a constant string; anything with a lifecycle (ViewModels, services) uses a per-instance id (`"Name-${System.identityHashCode(this)}"`) and calls `ProgressEvents.subscriber.stop(id)` in `onCleared`/`onDestroy`. Never derive ids from stack traces.
- **Handler bodies must not throw** — they run on a shared dispatch thread. Wrap risky work in `runCatching` (see `MainEventHandler.safely`).
- **Payloads are untrusted**: read with `as?` and handle null.

Key events: `WatchInitializationCompleted`, `ConnectionSetupComplete`, `ButtonPressedInfoReceived`, `DeviceAppeared`, `DeviceDisappeared`, `Disconnect`, `CalendarUpdated`, `SnackbarMessage`.

### Coroutines

- **`AppScopes.io` / `.default` / `.main`** (`utils/AppScopes.kt`): supervised application scopes with a logging `CoroutineExceptionHandler`. Use these for fire-and-forget work outside a lifecycle scope. Never create a bare `CoroutineScope(Dispatchers.X)`.
- **`WatchSyncActivity.track { }`** wraps long-running watch writes (time, alarms, events, settings) so the inactivity timer won't disconnect mid-sync.
- **`WatchConnectCoordinator.connect(address)`** serializes all connection attempts behind a mutex with a 30 s timeout. Never call `repository.waitForConnection` directly.

### Navigation

After connection, `BottomNavigationBarWithPermissions` hosts a Compose `NavHost` with tabs: **Time**, **Alarms**, **Events** (only if `WatchInfo.hasReminders`), **Settings**, plus a **SyncHistory** screen, and a **Debug** (BLE debug panel) tab that exists only in debug builds. Routes are defined in `Screens.kt` as sealed objects. The Events tab requires the calendar runtime permission.

A 3-minute inactivity timer (`InactivityHandler`) disconnects the watch when the user stops interacting — deferred while `WatchSyncActivity` reports a sync in flight.

### BLE Connection Flow

Two detection sources work in parallel, deduplicated by `DeviceEventGate` (CDM has priority; CDM events dedup only against CDM history):

1. **CompanionDeviceManager (CDM)** — preferred. `GShockCompanionDeviceService` (a `CompanionDeviceService`) fires `DeviceAppeared`/`DeviceDisappeared` events via CDM callbacks; handles API 31/32, 33+, and 35+ callback variants. Promotes itself to a foreground service on `DeviceAppeared` and demotes on `DeviceDisappeared`.
2. **BLE fallback scan** (`BleScanReceiver`) — safety net for ROMs that don't fully implement CDM, and the only automatic path on Android < 12. Requires FINE location on Android 10–11.

`CompanionDevicePresenceMonitor` subscribes to `DeviceAppeared` and connects via `WatchConnectCoordinator`. `DeviceAssociationManager` manages the pairing lifecycle: syncing CDM associations to `LocalDataStorage`, starting/stopping presence observation, and starting the fallback scan (`syncAssociations` is mutex-serialized). `BootReceiver` re-runs `syncAssociations` after reboot.

### MVVM Pattern

Each screen has a ViewModel (`@HiltViewModel`) following unidirectional data flow:
- `StateFlow` for persistent screen state
- `SharedFlow<UiEvent>` for one-time events like snackbar messages
- Sealed `Action`/`UiEvent` classes for type-safe interactions

### Scratchpad Storage (Watch Memory)

The watch exposes a small scratchpad memory used to persist app-side settings across reconnections, with a fixed-offset buffer layout (see `SCRATCHPAD_LAYOUT.md`):

| Offset | Size | Client |
|--------|------|--------|
| 0 | 3 bytes | `AlarmNameStorage` — 6 alarm names, 3-bit packed |

`ScratchpadManager` coordinates load/save behind a mutex. Each `ScratchpadClient` declares a fixed `getStorageOffset()` — registration order does not matter. To add a new client, implement `ScratchpadClient`, declare the next available offset (currently 3), and register in `init`.

### Local Persistence

`LocalDataStorage` wraps **DataStore Preferences** (migrated from SharedPreferences). Note: its synchronous `get()` uses `runBlocking` — avoid calling it from the main thread; prefer the suspend accessors/Flows. Per-watch keys are suffixed with the sanitized MAC address. The DataStore file lives at:
`/data/data/com.beamburst.casswatch/files/datastore/CASIO_GOOGLE_SYNC_STORAGE.preferences_pb`

Sync outcomes are recorded via `SyncHistoryLogger`/`SyncHistoryStore` and shown on the Sync History screen — every watch write should be recorded there.

### Time source and network use

`WatchTimeProvider`/`WatchTimeWriter` (`utils/NtpTime.kt`) choose the time written to the watch: phone time by default, or an SNTP offset when the user enables the NTP option in Settings (default server `pool.ntp.org`, UDP port 123, off by default). This is the only network use in the app. `docs/index.html` (the privacy policy) and `THIRD_PARTY_NOTICES.md` describe app behaviour and dependencies; keep them in step when permissions, network use, stored data or dependencies change.

### Services

- `GShockCompanionDeviceService` — `CompanionDeviceService` that handles CDM presence callbacks; foreground while a watch is present.
- `BootReceiver` — re-arms CDM association sync and the BLE fallback scan after device reboot.

## Key Conventions

- **Never call BLE directly.** All watch I/O goes through `GShockRepository` (which delegates to `GShockAPI`).
- **WatchInfo for capability checks.** Use `WatchInfo.hasReminders`, `WatchInfo.alwaysConnected`, `WatchInfo.chimeInSettings`, etc. to conditionally enable features for the connected model.
- **Timber for logging.** `Timber.d/i/w/e(...)` everywhere; `Timber.DebugTree` is only planted in debug builds.
- **AppSnackbar for user feedback.** Call `AppSnackbar(message)` rather than managing snackbar state in composables directly.
- **Strings in `res/values/strings.xml`.** The app is localized into 11 languages besides English (`values-*` folders); never hardcode user-visible strings, and add translations to every locale folder unless the string is marked `translatable="false"`.
- **`@ApplicationContext` for context injection.** Always inject application context (not activity context) into singletons and ViewModels.
- **Gson-serialized models need R8 keep rules.** Anything persisted as JSON by field name must be covered in `app/proguard-rules.pro`, or release builds will silently corrupt storage.

## Tech Stack

- Kotlin 2.2.0, AGP 8.12.3, compileSdk 36, minSdk 26, Java 21
- Jetpack Compose (Material3) + ConstraintLayout Compose
- Hilt 2.57.2 for DI
- DataStore Preferences for local storage
- RRule for calendar recurrence rule parsing
- Timber for logging
- Gson for JSON persistence
- GShockAPI (external library, `com.github.cihyboj:GShockAPI` via JitPack)
