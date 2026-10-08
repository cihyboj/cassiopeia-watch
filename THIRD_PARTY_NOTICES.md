# Third-party notices

Cassiopeia Watch is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`).
It is a derivative of CasioGShockSmartSync by Ivo Zivkov (Apache-2.0,
https://github.com/izivkov/CasioGShockSmartSync).

This file lists every module resolved on the Gradle `releaseRuntimeClasspath`
configuration of the app, i.e. what is packaged in the release APK, with the version
that actually resolves (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`).
Licences were read from each artifact's published POM (Maven Central or Google's Maven
repository), from the project's repository (GShockAPI, RRule, Nordic libraries) or from
the licence file inside the artifact (SLF4J). Build-time tools (Hilt/Dagger compilers, Kotlin
compiler, Android Gradle Plugin) and test-only libraries are not shipped and are not listed.
The APK also contains this file, `LICENSE` and `NOTICE` under `assets/`.

Direct dependencies: GShockAPI (fork), RRule, Gson, Timber, Dagger/Hilt, AndroidX (core, appcompat,
constraintlayout, lifecycle, navigation, exifinterface, datastore, hilt, compose), Material 3 for
Compose, ConstraintLayout Compose, Compose Material icons, `transport-api`. Everything else is
transitive.

## Non-AndroidX modules

| Group | Modules and resolved versions | Licence | Project |
|---|---|---|---|
| `com.github.PhilJay` | `RRule` 1.0.3 | Apache-2.0 (GitHub repository licence) | https://github.com/PhilJay/RRule |
| `com.github.cihyboj` | `GShockAPI` 042cc1e | MIT | https://github.com/cihyboj/GShockAPI |
| `com.google.android.datatransport` | `transport-api` 4.1.1 | Apache-2.0 | https://developer.android.com/ |
| `com.google.android.material` | `material` 1.4.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| `com.google.code.findbugs` | `jsr305` 3.0.2 | Apache-2.0 | https://findbugs.sourceforge.net/ |
| `com.google.code.gson` | `gson` 2.13.2 | Apache-2.0 | https://github.com/google/gson |
| `com.google.dagger` | `dagger` 2.57.2, `dagger-lint-aar` 2.57.2, `hilt-android` 2.57.2, `hilt-core` 2.57.2 | Apache-2.0 | https://github.com/google/dagger |
| `com.google.errorprone` | `error_prone_annotations` 2.41.0 | Apache-2.0 | https://github.com/google/error-prone |
| `com.google.guava` | `listenablefuture` 1.0 | Apache-2.0 (inherited from guava-parent 26.0-android) | https://github.com/google/guava |
| `com.jakewharton.timber` | `timber` 5.0.1 | Apache-2.0 | https://github.com/JakeWharton/timber |
| `com.squareup.okio` | `okio` 3.9.1, `okio-jvm` 3.9.1 | Apache-2.0 | https://github.com/square/okio |
| `jakarta.inject` | `jakarta.inject-api` 2.0.1 | Apache-2.0 | https://github.com/eclipse-ee4j/injection-api |
| `javax.inject` | `javax.inject` 1 | Apache-2.0 | https://github.com/javax-inject/javax-inject |
| `no.nordicsemi.android` | `ble` 2.11.0 | BSD-3-Clause | https://github.com/NordicSemiconductor/Android-BLE-Library |
| `no.nordicsemi.android.kotlin.ble` | `client` 1.3.1, `client-android` 1.3.1, `client-api` 1.3.1, `client-mock` 1.3.1, `core` 1.3.1, `mock` 1.3.1, `server-api` 1.3.1 | BSD-3-Clause | https://github.com/NordicSemiconductor/Kotlin-BLE-Library |
| `org.jetbrains` | `annotations` 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| `org.jetbrains.kotlin` | `kotlin-android-extensions-runtime` 2.1.0, `kotlin-parcelize-runtime` 2.1.0, `kotlin-stdlib` 2.2.0, `kotlin-stdlib-common` 2.2.0, `kotlin-stdlib-jdk7` 1.8.0, `kotlin-stdlib-jdk8` 1.8.0 | Apache-2.0 | https://kotlinlang.org/ |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-android` 1.9.0, `kotlinx-coroutines-bom` 1.9.0, `kotlinx-coroutines-core` 1.9.0, `kotlinx-coroutines-core-jvm` 1.9.0, `kotlinx-serialization-bom` 1.7.3, `kotlinx-serialization-core` 1.7.3, `kotlinx-serialization-core-jvm` 1.7.3, `kotlinx-serialization-json` 1.7.3, `kotlinx-serialization-json-jvm` 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines and https://github.com/Kotlin/kotlinx.serialization |
| `org.jspecify` | `jspecify` 1.0.0 | Apache-2.0 | https://jspecify.org/ |
| `org.slf4j` | `slf4j-api` 2.0.13 | MIT (see below) | https://www.slf4j.org |

Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0.txt

## AndroidX and Jetpack Compose modules

All Apache-2.0 per their POMs, except as noted.

| Group | Modules and resolved versions | Licence | Project |
|---|---|---|---|
| `androidx.activity` | `activity` 1.8.2, `activity-compose` 1.8.2, `activity-ktx` 1.8.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.annotation` | `annotation` 1.9.1, `annotation-experimental` 1.4.1, `annotation-jvm` 1.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.appcompat` | `appcompat` 1.7.1, `appcompat-resources` 1.7.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.arch.core` | `core-common` 2.2.0, `core-runtime` 2.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.autofill` | `autofill` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.cardview` | `cardview` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.collection` | `collection` 1.5.0, `collection-jvm` 1.5.0, `collection-ktx` 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.animation` | `animation` 1.8.1, `animation-android` 1.8.1, `animation-core` 1.8.1, `animation-core-android` 1.8.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.foundation` | `foundation` 1.8.1, `foundation-android` 1.8.1, `foundation-layout` 1.8.1, `foundation-layout-android` 1.8.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.material` | `material-icons-core` 1.7.8, `material-icons-core-android` 1.7.8, `material-icons-extended` 1.7.8, `material-icons-extended-android` 1.7.8, `material-ripple` 1.8.1, `material-ripple-android` 1.8.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.material3` | `material3` 1.4.0, `material3-android` 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.runtime` | `runtime` 1.10.6, `runtime-android` 1.10.6, `runtime-annotation` 1.10.6, `runtime-annotation-android` 1.10.6, `runtime-retain` 1.10.6, `runtime-retain-android` 1.10.6, `runtime-saveable` 1.10.6, `runtime-saveable-android` 1.10.6 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.compose.ui` | `ui` 1.10.6, `ui-android` 1.10.6, `ui-geometry` 1.10.6, `ui-geometry-android` 1.10.6, `ui-graphics` 1.10.6, `ui-graphics-android` 1.10.6, `ui-text` 1.10.6, `ui-text-android` 1.10.6, `ui-tooling-preview` 1.10.6, `ui-tooling-preview-android` 1.10.6, `ui-unit` 1.10.6, `ui-unit-android` 1.10.6, `ui-util` 1.10.6, `ui-util-android` 1.10.6 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.concurrent` | `concurrent-futures` 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.constraintlayout` | `constraintlayout` 2.2.1, `constraintlayout-compose` 1.1.1, `constraintlayout-compose-android` 1.1.1, `constraintlayout-core` 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.coordinatorlayout` | `coordinatorlayout` 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.core` | `core` 1.18.0, `core-ktx` 1.18.0, `core-viewtree` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.cursoradapter` | `cursoradapter` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.customview` | `customview` 1.1.0, `customview-poolingcontainer` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.datastore` | `datastore` 1.2.1, `datastore-android` 1.2.1, `datastore-core` 1.2.1, `datastore-core-android` 1.2.1, `datastore-core-okio` 1.2.1, `datastore-core-okio-jvm` 1.2.1, `datastore-preferences` 1.2.1, `datastore-preferences-android` 1.2.1, `datastore-preferences-core` 1.2.1, `datastore-preferences-core-android` 1.2.1, `datastore-preferences-external-protobuf` 1.2.1, `datastore-preferences-proto` 1.2.1 | Apache-2.0; `datastore-preferences-external-protobuf` is BSD-3-Clause (bundled protobuf-lite) | https://developer.android.com/jetpack/androidx |
| `androidx.documentfile` | `documentfile` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.drawerlayout` | `drawerlayout` 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.dynamicanimation` | `dynamicanimation` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.emoji2` | `emoji2` 1.4.0, `emoji2-views-helper` 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.exifinterface` | `exifinterface` 1.4.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.fragment` | `fragment` 1.6.2, `fragment-ktx` 1.6.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.graphics` | `graphics-path` 1.0.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.hilt` | `hilt-lifecycle-viewmodel` 1.3.0, `hilt-lifecycle-viewmodel-compose` 1.3.0, `hilt-navigation-compose` 1.3.0, `hilt-navigation-fragment` 1.3.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.interpolator` | `interpolator` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.legacy` | `legacy-support-core-utils` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.lifecycle` | `lifecycle-common` 2.10.0, `lifecycle-common-java8` 2.10.0, `lifecycle-common-jvm` 2.10.0, `lifecycle-livedata` 2.10.0, `lifecycle-livedata-core` 2.10.0, `lifecycle-livedata-core-ktx` 2.10.0, `lifecycle-livedata-ktx` 2.10.0, `lifecycle-process` 2.10.0, `lifecycle-runtime` 2.10.0, `lifecycle-runtime-android` 2.10.0, `lifecycle-runtime-compose` 2.10.0, `lifecycle-runtime-compose-android` 2.10.0, `lifecycle-runtime-ktx` 2.10.0, `lifecycle-runtime-ktx-android` 2.10.0, `lifecycle-service` 2.10.0, `lifecycle-viewmodel` 2.10.0, `lifecycle-viewmodel-android` 2.10.0, `lifecycle-viewmodel-compose` 2.10.0, `lifecycle-viewmodel-compose-android` 2.10.0, `lifecycle-viewmodel-ktx` 2.10.0, `lifecycle-viewmodel-savedstate` 2.10.0, `lifecycle-viewmodel-savedstate-android` 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.loader` | `loader` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.localbroadcastmanager` | `localbroadcastmanager` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.navigation` | `navigation-common` 2.9.7, `navigation-common-android` 2.9.7, `navigation-compose` 2.9.7, `navigation-compose-android` 2.9.7, `navigation-fragment` 2.9.7, `navigation-fragment-ktx` 2.9.7, `navigation-runtime` 2.9.7, `navigation-runtime-android` 2.9.7, `navigation-ui` 2.9.7, `navigation-ui-ktx` 2.9.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.print` | `print` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.profileinstaller` | `profileinstaller` 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.recyclerview` | `recyclerview` 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.resourceinspection` | `resourceinspection-annotation` 1.0.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.savedstate` | `savedstate` 1.4.0, `savedstate-android` 1.4.0, `savedstate-compose` 1.4.0, `savedstate-compose-android` 1.4.0, `savedstate-ktx` 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.slidingpanelayout` | `slidingpanelayout` 1.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.startup` | `startup-runtime` 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.tracing` | `tracing` 1.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.transition` | `transition` 1.6.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.vectordrawable` | `vectordrawable` 1.1.0, `vectordrawable-animated` 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.versionedparcelable` | `versionedparcelable` 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.viewpager` | `viewpager` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.viewpager2` | `viewpager2` 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.window` | `window` 1.5.0, `window-core` 1.5.0, `window-core-android` 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |

## Licence texts

### GShockAPI (MIT)

Cassiopeia Watch builds against a fork of GShockAPI at commit
`042cc1e2b5d4f5a325d70293dabe0497bc9d11e1`. The fork adds changes to time
setting (removal of an incorrect manual DST adjustment, correct sub-second
fields in the CURRENT_TIME packet, and a `primeTimeCache()` call). The licence
text from that repository:

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

### Nordic Android BLE Library 2.11.0 (`no.nordicsemi.android:ble`), BSD-3-Clause

From https://github.com/NordicSemiconductor/Android-BLE-Library (tag 2.11.0, file `LICENSE`):

```
Copyright (c) 2015, Nordic Semiconductor
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of Nordic Semiconductor ASA nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Nordic Kotlin BLE Library 1.3.1 (`no.nordicsemi.android.kotlin.ble:*`), BSD-3-Clause

From https://github.com/NordicSemiconductor/Kotlin-BLE-Library (tag 1.3.1, file `LICENSE`):

```
Copyright (c) 2015, Nordic Semiconductor
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of Kotlin-BLE-Library nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### SLF4J API 2.0.13 (MIT)

From `META-INF/LICENSE.txt` inside `slf4j-api-2.0.13.jar`. The `slf4j-api` POM does not declare a
licence itself; it inherits through `slf4j-parent` from `slf4j-bom` 2.0.13, whose POM declares "MIT License".

```
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Artwork

The app icon and the in-app watch artwork (`cassiopeia_watch`, `images/cassiopeia-watch.png`) are original to
Cassiopeia Watch. The inherited Casio watch product photos that earlier builds contained have been removed.
The remaining drawables that come from CasioGShockSmartSync are simple vector icons (calendar, alarm and
clock icons); they keep their original headers, see `NOTICE`.
