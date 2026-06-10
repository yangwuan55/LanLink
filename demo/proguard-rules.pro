# ProGuard rules for LanLink App

# Keep lanlink-core
-keep class com.ymr.lanlink.core.** { *; }
-keepclassmembers class com.ymr.lanlink.core.** { *; }

# Keep domain models for ViewModel
-keep class com.ymr.lanlink.core.domain.model.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Keep custom application class
-keep class com.ymr.lanlink.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**