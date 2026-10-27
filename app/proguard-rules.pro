# --- Native TUN bridge -------------------------------------------------------
# libhev-socks5-tunnel registers its natives against this exact class path in
# JNI_OnLoad. Renaming or removing it is an instant UnsatisfiedLinkError.
-keep class com.ars3nb.volta.core.vpn.Tun2Socks { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Xray core (libv2ray) ----------------------------------------------------
# Reached entirely through reflection, and the gomobile bridge needs its own
# classes intact.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# --- Data model --------------------------------------------------------------
# Gson maps these by field name, and Room reads them by constructor parameter.
-keep class com.ars3nb.volta.data.model.** { *; }
-keepclassmembers enum com.ars3nb.volta.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson's TypeToken relies on generic signatures surviving.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# --- WorkManager -------------------------------------------------------------
-keep class com.ars3nb.volta.worker.** { *; }

# --- OkHttp ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
