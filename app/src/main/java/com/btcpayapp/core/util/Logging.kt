package com.btcpayapp.core.util

import com.btcpayapp.BuildConfig

/**
 * Logging is a data-exfiltration channel: logcat is readable by the user, by
 * `adb`, and historically by other apps on rooted or badly-patched devices.
 * Every call here compiles down to nothing in release (the `if` is constant
 * folded, and R8 additionally strips `android.util.Log` via a rule).
 *
 * Nothing in this app ever logs an API key, a BOLT11 invoice, a derivation
 * scheme, a seed, or a full response body. [redact] exists for the cases where
 * some identifying fragment genuinely helps debugging.
 */
internal object Log {

    // `inline` is what makes the claim above true. As ordinary functions these
    // would allocate a capturing lambda at every call site and make a real call
    // that checks `BuildConfig.DEBUG` at runtime — including inside the read
    // path of `EncryptedJsonFile`.
    // Inlined, the constant folds at the call site and both the lambda and the
    // argument expression disappear from release builds entirely.
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, message())
    }

    inline fun w(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.w(tag, message())
    }

    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        // Serialization and transport exceptions may embed response bodies or
        // credential-bearing URLs in their messages and nested causes.
        if (BuildConfig.DEBUG) android.util.Log.e(tag, message() +
            (throwable?.let { " [${it.javaClass.simpleName}]" } ?: ""))
    }

    /** Keeps the first and last two characters so a value can be told apart. */
    fun redact(value: String?): String = when {
        value == null -> "null"
        value.length <= 6 -> "***"
        else -> "${value.take(2)}…${value.takeLast(2)}(${value.length})"
    }
}
