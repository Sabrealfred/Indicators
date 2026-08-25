# kotlinx.serialization keeps generated serializers referenced only by reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.neopal.pet.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.neopal.pet.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The on-device engine is called from native code, which R8 cannot see.
#
# LiteRT-LM's JNI layer looks its Kotlin classes and callbacks up by name from C++. R8 has no
# reference to follow to them, so under the release build's minification it is free to rename or
# remove exactly the members the native side is about to ask for. The library is expected to ship
# its own consumer rules; this does not depend on that being true, because the failure mode is a
# release-only crash on a device, and CI builds and tests the debug variant, where minification is
# off. Nothing local can see this one at all.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** {
    native <methods>;
}
