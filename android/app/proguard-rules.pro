# Pleos SDKs are reached by reflection (vehicle/Reflect.kt): keep their names and members.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# Kotlin function types handed to the SDK as callbacks
-keep class kotlin.jvm.functions.** { *; }
# The judging engine reads its bundled config and parity data by resource name
-keepclassmembers class com.safelog.core.** { *; }
# Gson inside the Pleos SDK reads generic types from TypeToken subclasses. Without their signatures the SDK's
# capability checks fail in a minified build ("TypeToken must be created with a type argument").
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
