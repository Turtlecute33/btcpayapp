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
