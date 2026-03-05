# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ProGuard / R8 混淆规则文件
# 该文件解决了所有数据模型（Gson/Protobuf/枚举/Kotlinx.Serialization）和组件（Glance/Worker/WebView）的混淆问题。

# -------------------------------------------------------------------------
# 1. 基础全局设置 (保留必要的调试和反射元数据)
# -------------------------------------------------------------------------

# 保留源代码文件和行号，用于在 Release 版崩溃时还原堆栈
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,AnnotationDefault

# -------------------------------------------------------------------------
# 2. 原生小组件 (Native Widget) 规则
# -------------------------------------------------------------------------

# 保留所有小组件 Provider (确保系统能实例化它们)
-keep public class * extends android.appwidget.AppWidgetProvider {
    public void *(android.content.Context, android.content.Intent);
    <init>();
}

# 保留小组件渲染和业务逻辑包
-keep class com.shiro.classflow.widget.** { *; }

# -------------------------------------------------------------------------
# 3. WorkManager 规则 (确保同步任务不失效)
# -------------------------------------------------------------------------

# 保留所有 Worker 的类名和构造函数，供 WorkManager 反射调用
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# -------------------------------------------------------------------------
# 4. Retrofit 3 & OkHttp 规则 (官方推荐方案)
# -------------------------------------------------------------------------

# Retrofit 3 已经很好地适配了 R8，但为了保险，保留接口上的注解
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp 混淆规避
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# -------------------------------------------------------------------------
# 5. JGit & SLF4J 优化规则 (深度瘦身)
# -------------------------------------------------------------------------

# 忽略 JGit 一些在 Android 上不存在的 Java 管理库警告
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.commons.compress.**

# 保留 JGit 核心类
-keep class org.eclipse.jgit.** { *; }

# SLF4J Android 桥接保留
-keep class org.slf4j.impl.** { *; }
-dontwarn org.slf4j.**

# -------------------------------------------------------------------------
# 6. Protobuf Lite 规则 (防止 NoSuchFieldException)
# -------------------------------------------------------------------------

-keep class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

-keep enum * implements com.google.protobuf.Internal$EnumLite {
    *;
}

# -------------------------------------------------------------------------
# 7. Kotlinx Serialization 规则 (核心逻辑)
# -------------------------------------------------------------------------

# Kotlinx Serialization 核心规则
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# 自动保留 Serializable 类及其 Companion
-keep @kotlinx.serialization.Serializable class * {
    ** Companion;
}

# 保留编译器生成的序列化器核心方法
-keepclassmembers class * {
    *** write$Self(...);
    <init>(int, ...);
}

# 保留自动生成的序列化器类
-keep class **$$serializer { *; }

# -------------------------------------------------------------------------
# 8. WebView & AndroidBridge
# -------------------------------------------------------------------------

# 严禁混淆 JavaScript 接口方法名
-keep class com.shiro.classflow.ui.schoolselection.web.AndroidBridge { *; }
-keepclassmembers class com.shiro.classflow.ui.schoolselection.web.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# -------------------------------------------------------------------------
# 9. 数据模型 & 数据库 (Room)
# -------------------------------------------------------------------------

# 彻底删除了 Gson 规则。
# 保留 Room 实体类和 Data Model，防止数据库字段解析失败
-keep class com.shiro.classflow.data.db.** { *; }
-keep class com.shiro.classflow.data.model.** { *; }

# -------------------------------------------------------------------------
# 10. 极简优化：Release 版本自动删除所有 Log.d (调试日志)
# -------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# -------------------------------------------------------------------------
# 11. IntroShowcase 引导库 (反射调用 internal setter 需要保留)
# -------------------------------------------------------------------------
-keep class com.canopas.lib.showcase.component.IntroShowcaseState {
    *;
}
