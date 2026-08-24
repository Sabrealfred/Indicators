# kotlinx.serialization keeps generated serializers referenced only by reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.neopal.pet.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.neopal.pet.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
