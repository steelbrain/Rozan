package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import java.util.concurrent.ThreadFactory

/**
 * Runs [block] at a thread boundary (a connection thread, the event thread, housekeeping): nothing it
 * throws may escape and kill the thread, including a stack overflow from hostile input (01 §3.5).
 * Failures are logged as [what] with the exception's class and message, which never carry secrets.
 */
@Suppress("TooGenericExceptionCaught") // A boundary: every failure is logged, none may end the thread.
internal fun guarded(
    log: ReceiverLog,
    what: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: RuntimeException) {
        log.log(ReceiverLog.Level.WARN, "$what failed: ${describe(e)}")
    } catch (e: StackOverflowError) {
        log.log(ReceiverLog.Level.WARN, "$what failed: ${describe(e)}")
    }
}

/** `Class: message`, the message cut to 160 characters. */
internal fun describe(error: Throwable): String =
    error.javaClass.simpleName + (error.message?.let { ": " + it.take(MAX_MESSAGE_CHARS) } ?: "")

private const val MAX_MESSAGE_CHARS = 160

/** Daemon threads named [name]. */
internal fun daemonThreads(name: String): ThreadFactory =
    ThreadFactory { task -> Thread(task, name).also { it.isDaemon = true } }
