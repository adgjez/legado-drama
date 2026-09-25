# drama 模块 R8 保留规则
# Media3 Transformer 回调在消费者进程内使用反射，保留其监听器方法签名
-keepclassmembers class * extends androidx.media3.transformer.Transformer$Listener {
    <methods>;
}

# Room 生成的 DAO 实现
-keep class com.legado.drama.data.entity.** { *; }
-keep class com.legado.drama.data.dao.** { *; }

# kotlinx.serialization 生成器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.legado.drama.engine.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.legado.drama.engine.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}