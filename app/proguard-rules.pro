##############################################################
# Numify — R8 / ProGuard rules
##############################################################

# Keep debug-friendly stack traces (Crashlytics de-obfuscation).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# -------------------------------------------------------------
# Android essentials
# -------------------------------------------------------------
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep <methods>; }
-keepclasseswithmembers class * { @androidx.annotation.Keep <fields>; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers class **.R$* { public static <fields>; }
-keepclassmembers class * { native <methods>; }

# View/Data binding generated classes.
-keep class com.callerid.numberlookup.home.databinding.** { *; }

# -------------------------------------------------------------
# App models — serialized by Gson (Retrofit) & parsed from
# Firebase Remote Config JSON. Field names must survive.
# -------------------------------------------------------------
-keep class com.callerid.numberlookup.home.entity.** { *; }
-keep class com.callerid.numberlookup.home.monetize.model.** { *; }
-keepclassmembers class com.callerid.numberlookup.home.entity.** { *; }

# -------------------------------------------------------------
# Kotlin
# -------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# -------------------------------------------------------------
# Gson
# -------------------------------------------------------------
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation,allowshrinking class * {
    @com.google.gson.annotations.Expose <fields>;
}
-dontwarn com.google.gson.**

# -------------------------------------------------------------
# Retrofit + OkHttp + Okio
# -------------------------------------------------------------
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# -------------------------------------------------------------
# libphonenumber (offline geocoder / carrier / validation)
# Metadata lives in JAR resources — keep the classes that load it.
# -------------------------------------------------------------
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**

# -------------------------------------------------------------
# Ads & monetization SDKs
# -------------------------------------------------------------
# Google Mobile Ads
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.**
# Facebook Audience Network + Core SDK
-keep class com.facebook.** { *; }
-dontwarn com.facebook.**
# LightHouse push SDK ships its own consumer ProGuard rules in the AAR; the
# Firebase + Gson keeps below cover its FCM + JSON needs. (Replaced OneSignal.)
# Ad module — AdAwareActivity is an open base (subclassed) that drives Remote
# Config init/ad loading; keep it and its members intact.
-keep class com.callerid.numberlookup.home.monetize.delivery.AdAwareActivity { *; }

# -------------------------------------------------------------
# Firebase / Crashlytics / Remote Config
# -------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# -------------------------------------------------------------
# Google Play (in-app update) & install referrer
# -------------------------------------------------------------
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**
-keep class com.android.installreferrer.** { *; }

# -------------------------------------------------------------
# Glide
# -------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# -------------------------------------------------------------
# Lottie / Shimmer (resource-driven, no reflection issues)
# -------------------------------------------------------------
-dontwarn com.airbnb.lottie.**
-dontwarn com.facebook.shimmer.**

# Dexter (runtime permissions)
-dontwarn com.karumi.dexter.**

# -------------------------------------------------------------
# Room (and Room-backed libs like WorkManager)
# Room instantiates its generated *_Impl database classes via
# reflection (Room.getGeneratedImplementation -> getDeclaredConstructor()).
# R8 full-mode strips those no-arg constructors, causing:
#   NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# Keep every RoomDatabase subclass' no-arg constructor.
# -------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.**

# -------------------------------------------------------------
# WorkManager — auto-initialized at startup via androidx.startup.
# Keep its Room DB impl and reflectively-constructed Workers.
# -------------------------------------------------------------
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# -------------------------------------------------------------
# Permission Engine + Full-Screen-Intent (FSI) flow
# (com.callerid.numberlookup.home.permission.**)
#
# Most of this package needs NO rules:
#  • LockScreenAlertActivity / LockScreenWatchService are declared in the manifest, so
#    R8 keeps them (and their entry points) automatically.
#  • LockScreenConfig / PermissionModels / the RC parsers read org.json with literal
#    string keys — no Gson, no reflection — so field/class names may be
#    obfuscated freely.
#  • PermissionCoordinator, PermissionRepository, LockScreenReturnWatcher, etc. are called /
#    registered directly in code and kept as reachable.
#
# The ONLY reflective surface is the FragmentManager re-instantiating the
# engine's Fragment / DialogFragment BY NAME after a configuration change or
# process death. Keep their no-arg constructors so that path can never
# NoSuchMethod-crash under R8 full mode.
# -------------------------------------------------------------
-keepclassmembers class com.callerid.numberlookup.home.permission.PermissionLauncher {
    <init>();
}
-keepclassmembers class com.callerid.numberlookup.home.permission.PermissionSheetDialog {
    <init>();
}
