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

# MediaPipe (auto cut-out) uses JNI and protobuf reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
