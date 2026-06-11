package com.ymr.lanlink.core.platform

/**
 * Cross-platform logging abstraction. Each call site passes its own [tag]
 * (preserving the per-class TAG conventions from the Android implementation).
 *
 * The default implementation prints to stdout; platforms may inject their own
 * implementation by setting the global [logger] (e.g. androidMain wires an
 * AndroidLogger backed by android.util.Log).
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

/**
 * Default [Logger] that writes to stdout. Used until a platform installs its own.
 */
class PrintlnLogger : Logger {
    override fun d(tag: String, msg: String) {
        println("D/$tag: $msg")
    }

    override fun i(tag: String, msg: String) {
        println("I/$tag: $msg")
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        println("W/$tag: $msg")
        t?.let { println(it.stackTraceToString()) }
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        println("E/$tag: $msg")
        t?.let { println(it.stackTraceToString()) }
    }
}

/**
 * Globally installed [Logger]. Platforms (or callers) may reassign this to
 * route logging through a platform-native backend.
 */
var logger: Logger = PrintlnLogger()
