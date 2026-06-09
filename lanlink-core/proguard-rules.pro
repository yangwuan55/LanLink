# ProGuard rules for LanLink Core Library

# Keep all protobuf classes
-keep class com.ymr.lanlink.core.proto.** { *; }
-keepclassmembers class com.ymr.lanlink.core.proto.** { *; }

# Keep domain models
-keep class com.ymr.lanlink.core.domain.model.** { *; }
-keepclassmembers class com.ymr.lanlink.core.domain.model.** { *; }

# Keep auth classes
-keep class com.ymr.lanlink.core.domain.auth.** { *; }
-keepclassmembers class com.ymr.lanlink.core.domain.auth.** { *; }

# Protobuf
-dontwarn com.google.protobuf.**
-keep class com.google.protobuf.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Keep generic signatures and annotations
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses