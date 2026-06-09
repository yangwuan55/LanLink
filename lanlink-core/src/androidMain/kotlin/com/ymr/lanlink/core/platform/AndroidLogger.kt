package com.ymr.lanlink.core.platform

import android.util.Log

/**
 * [Logger] backed by android.util.Log so logcat debugging is preserved.
 */
class AndroidLogger : Logger {
    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }
}

/**
 * Installs [AndroidLogger] as the global [logger]. Referencing this object
 * (or any of its members) triggers the assignment via the class initializer.
 */
object AndroidLoggerInstaller {
    init {
        logger = AndroidLogger()
    }

    /** No-op accessor whose sole purpose is to force the initializer to run. */
    fun install() {}
}
