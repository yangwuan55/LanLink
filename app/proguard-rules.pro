# ProGuard rules for LanChat App

# Keep lanchat-core
-keep class com.ymr.lancomm.** { *; }
-keepclassmembers class com.ymr.lancomm.** { *; }

# Keep domain models for ViewModel
-keep class com.ymr.lancomm.domain.model.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Keep custom application class
-keep class com.example.lanchat.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**