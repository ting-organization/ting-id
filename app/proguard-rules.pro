# libsecp256k1's loader finds its JNI class by name.
-keep class fr.acinq.secp256k1.** { *; }
# kotlinx.serialization: the generated serializers of the app's saved state.
-keepclassmembers @kotlinx.serialization.Serializable class app.tingid.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses
# No logging of anything in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
