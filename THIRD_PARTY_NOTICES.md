# Third-party notices

Cassiopeia Watch is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`).
It is a derivative of CasioGShockSmartSync by Ivo Zivkov (Apache-2.0,
https://github.com/izivkov/CasioGShockSmartSync).

This file lists the libraries that are packaged in the release APK
(Gradle configuration `releaseRuntimeClasspath`, versions as pinned in
`gradle/libs.versions.toml`). Licences were read from each artifact's published
POM on Maven Central or Google's Maven repository, or from the project's
repository (GShockAPI, RRule). Build-time tools (Hilt/Dagger compilers, Kotlin
compiler, Android Gradle Plugin) and test-only libraries are not shipped and are not listed.
The APK also contains this file, `LICENSE` and `NOTICE` under `assets/`.

## Direct dependencies

| Library | Version | Licence | Project |
|---|---|---|---|
| GShockAPI (fork, `com.github.cihyboj:GShockAPI`) | commit `042cc1e2b5d4f5a325d70293dabe0497bc9d11e1` | MIT (text below) | https://github.com/cihyboj/GShockAPI (upstream: https://github.com/izivkov/GShockAPI) |
| RRule (`com.github.PhilJay:RRule`) | 1.0.3 | Apache-2.0 (GitHub repository licence) | https://github.com/PhilJay/RRule |
| Gson | 2.13.2 | Apache-2.0 | https://github.com/google/gson |
| Timber | 5.0.1 | Apache-2.0 | https://github.com/JakeWharton/timber |
| Dagger / Hilt (`hilt-android`, `hilt-core`, `dagger`) | 2.57.2 | Apache-2.0 | https://github.com/google/dagger |
| AndroidX Hilt (`hilt-navigation-compose`, `hilt-navigation-fragment`) | 1.3.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/hilt |
| AndroidX core-ktx | 1.18.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core |
| AndroidX AppCompat | 1.7.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/appcompat |
| AndroidX ConstraintLayout / ConstraintLayout Compose | 2.2.1 / 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/constraintlayout |
| AndroidX Lifecycle (livedata-ktx, viewmodel-ktx, service) | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| AndroidX Navigation (fragment-ktx, ui-ktx) | 2.9.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigation |
| AndroidX ExifInterface | 1.4.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/exifinterface |
| AndroidX DataStore Preferences | 1.2.1 | Apache-2.0 (its bundled protobuf-lite: BSD-3-Clause) | https://developer.android.com/jetpack/androidx/releases/datastore |
| Jetpack Compose (ui, ui-tooling-preview, runtime, foundation, animation, material-icons) | 1.10.6 (icons 1.7.8) | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose |
| Compose Material 3 | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Google Android Datatransport `transport-api` | 4.1.1 | Apache-2.0 | https://developer.android.com/ |

## Notable transitive dependencies

| Library | Version | Licence | Project |
|---|---|---|---|
| Kotlin standard library (`kotlin-stdlib` and related) | 2.2.0 (some modules 1.8.0 / 2.1.0) | Apache-2.0 | https://kotlinlang.org/ |
| kotlinx.coroutines (core, android) | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization (core, json) | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Nordic Android BLE Library (`no.nordicsemi.android:ble`) | 2.11.0 | BSD-3-Clause | https://github.com/NordicSemiconductor/Android-BLE-Library |
| Nordic Kotlin BLE Library (`no.nordicsemi.android.kotlin.ble:*`) | 1.3.1 | BSD-3-Clause | https://github.com/NordicPlayground/Kotlin-BLE-Library |
| Material Components for Android (`com.google.android.material:material`) | 1.4.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| Okio | 3.9.1 | Apache-2.0 | https://github.com/square/okio/ |
| JetBrains annotations | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| JSpecify | 1.0.0 | Apache-2.0 | http://jspecify.org/ |
| Error Prone annotations | 2.41.0 | Apache-2.0 | https://github.com/google/error-prone |
| javax.inject | 1 | Apache-2.0 | http://code.google.com/p/atinject/ |
| jakarta.inject-api | 2.0.1 | Apache-2.0 | https://github.com/eclipse-ee4j/injection-api |
| JSR-305 annotations | 3.0.2 | Apache-2.0 (as declared in the POM) | http://findbugs.sourceforge.net/ |
| Guava `listenablefuture` | 1.0 | licence not verified (POM declares none) | https://github.com/google/guava |
| SLF4J API | 2.0.13 | licence not verified (POM declares none) | http://www.slf4j.org |
| Other AndroidX libraries (activity, annotation, arch-core, collection, customview, emoji2, fragment, savedstate, startup, tracing, transition, window, and similar) | various | Apache-2.0 | https://developer.android.com/jetpack/androidx |

Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0.txt

## GShockAPI (MIT)

Cassiopeia Watch builds against a fork of GShockAPI at commit
`042cc1e2b5d4f5a325d70293dabe0497bc9d11e1`. The fork adds changes to time
setting (removal of an incorrect manual DST adjustment, correct sub-second
fields in the CURRENT_TIME packet, and a `primeTimeCache()` call). The licence
text from that repository follows.

```
MIT License

Copyright (c) 2023 Ivo Zivkov

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Artwork

The app icon and the in-app artwork are original to Cassiopeia Watch. Some vector
drawables (calendar, alarm and clock icons) originate from CasioGShockSmartSync;
see `NOTICE`.
