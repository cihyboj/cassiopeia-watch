# Keep attributes Gson needs to resolve generic types (TypeToken) and annotations.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# GShockAPI: its Settings/Alarm/etc. objects are serialized with Gson by this app
# (WatchSettingsCache, SettingsViewModel) and the library itself parses watch JSON
# by field name. Renamed fields would silently break settings load/save in release
# builds only.
-keep class org.avmedia.gshockapi.** { *; }
-dontwarn org.avmedia.gshockapi.**

# App models persisted as JSON via Gson: field names are the storage schema.
-keepclassmembers class com.beamburst.casswatch.ui.alarms.AlarmSyncStorage$StoredAlarm { <fields>; }
-keepclassmembers class com.beamburst.casswatch.data.local.** { <fields>; }

# Enum constant names are used in persisted JSON and DataStore values.
-keepclassmembers enum com.beamburst.casswatch.** { *; }
-keepclassmembers enum org.avmedia.gshockapi.** { *; }

# Gson TypeToken subclasses rely on reflective generic-signature lookup.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-dontwarn androidx.concurrent.futures.SuspendToFutureAdapter
