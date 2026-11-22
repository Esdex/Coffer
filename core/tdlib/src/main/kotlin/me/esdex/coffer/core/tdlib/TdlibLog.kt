package me.esdex.coffer.core.tdlib

/**
 * Where Coffer's lines about TDLib ([TAG]) go: logcat on Android, the desktop's log there. The platform
 * sets [sink] once at start; until then lines go to standard error.
 */
object TdlibLog {
    fun interface Sink {
        fun log(warning: Boolean, tag: String, message: String)
    }

    @Volatile
    var sink: Sink = Sink { warning, tag, message -> System.err.println("${if (warning) 'W' else 'I'}/$tag: $message") }

    internal fun i(message: String) = sink.log(false, TAG, message)

    internal fun w(message: String) = sink.log(true, TAG, message)
}
