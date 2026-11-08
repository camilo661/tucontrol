# The Anthropic SDK ships its own consumer R8 rules (META-INF/proguard), so no broad
# keep rules are needed here; that lets R8 strip the unused parts of the SDK.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.tucontrol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional JVM-only paths pulled in transitively by the Anthropic SDK:
# - victools JSON-schema generator (class-derived schemas; TuControl declares its schema manually)
# - AutoValue, a compile-time annotation referenced by OpenTelemetry
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType

# Vosk offline speech: JNA binds native functions to these classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
