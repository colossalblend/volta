# Release shrinking, obfuscation and optimisation (R8, on top of
# proguard-android-optimize.txt). Keep rules name only what is reached by name from
# outside the Kotlin code: JNI, reflection, Gson field names, WorkManager class names.
# Everything else is renamed, moved into one package and optimised.
# CI checks the result: the "Release APK keeps" step in .github/workflows/android.yml.

# --- Obfuscation -------------------------------------------------------------
# All renamed classes go into a single package with short names: a smaller dex and
# more room for R8 to inline across what used to be package boundaries.
-repackageclasses ''
-allowaccessmodification
# Readable crash reports: the mapping file turns these back into names and lines.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Logging -----------------------------------------------------------------
# Debug and info logs are compiled out of the release; warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Native TUN bridge -------------------------------------------------------
# libhev-socks5-tunnel registers its natives against this exact class path in
# JNI_OnLoad. Renaming the class or its native methods is an instant
# UnsatisfiedLinkError; its other members may be renamed.
-keep class com.ars3nb.volta.core.vpn.Tun2Socks {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- Xray core (libv2ray, gomobile) ------------------------------------------
# XrayCore reaches it entirely through reflection (Class.forName, getMethod and a
# java.lang.reflect.Proxy for the callback interface), and the gomobile bridge (go.Seq)
# calls back into its own classes from native code by name.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# --- Data model --------------------------------------------------------------
# Gson reads and writes these by field name (backups, routing rules), so field names
# stay; the classes themselves may be renamed and moved, and their methods optimised.
# Room needs nothing here: its generated code calls the constructors directly.
-keepclassmembers class com.ars3nb.volta.data.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers enum com.ars3nb.volta.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Generic field types (List<RoutingRuleDto>) and TypeToken need their signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- WorkManager -------------------------------------------------------------
# Scheduled work is stored by worker class name and survives app updates, so worker
# names never change between releases.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- OkHttp ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Bluetooth sharing (core/share) --------------------------------------------
# Nothing to keep: the GATT, scan and advertise callbacks are subclasses of framework
# classes (R8 keeps their overrides), and the crypto is the platform's JCA, looked up
# by algorithm name ("EC", "ECDH", "HmacSHA256", "AES/GCM/NoPadding"), not by class.
# The UUIDs, frames and status bytes are data, so renaming changes nothing on the air.
