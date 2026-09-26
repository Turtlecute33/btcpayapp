# R8 full mode is on (see gradle.properties).

# --- kotlinx.serialization -------------------------------------------------
# The plugin generates a $$serializer companion per @Serializable class and
# references it statically, so R8 keeps most of it. These rules cover the
# lookup paths that go through reflection on the companion object.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- Coroutines ------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- ZXing -----------------------------------------------------------------
# Only the QR encoder/decoder paths are used; the rest is shrunk away.
-dontwarn com.google.zxing.**
-dontnote com.google.zxing.**

# --- Strip logging from release builds -------------------------------------
# Belt and braces: the app already routes logging through a no-op in release,
# but this removes any stray android.util.Log call and its string arguments.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# --- Keep nothing reflective by accident -----------------------------------
# The app performs no reflection of its own. If this rule ever needs relaxing,
# that is a design smell worth fixing instead.
-dontusemixedcaseclassnames
-verbose
