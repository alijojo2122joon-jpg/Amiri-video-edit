# kotlinx.serialization — keep generated serializers for the project model.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.amiri.cut.core.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.amiri.cut.core.model.**$$serializer { *; }
-keepclasseswithmembers class com.amiri.cut.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
