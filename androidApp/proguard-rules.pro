# Boxlet Android · R8 规则
#
# 反混淆：release 构建会生成 androidApp/build/outputs/mapping/release/mapping.txt，
# 崩溃栈先用它 retrace（或上传 Play Console）再排查。下面保留行号信息，
# 让栈里至少还有行号可对。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization：@Serializable 类的 serializer() 是反射入口，
# 库自带 consumer 规则，这里额外兜底保留 Companion 与生成的 serializer。
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle：Provider 内部按字符串反射加载算法实现（bcprov 1.79 未内置 R8 规则），
# 只保留注册入口会在运行时报 "no such algorithm"，因此整包保留。
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# OkHttp / Okio 自带 consumer 规则，这里只压掉可选的平台依赖告警。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# Compose / Material3 由 AGP 的默认规则处理；这里不再重复添加。

# 调试期排查"某个类是否被裁掉"时，可临时打开（会让地图变大，不要用于发布）：
# -printusage build/outputs/mapping/release/usage.txt
# -printseeds build/outputs/mapping/release/seeds.txt
