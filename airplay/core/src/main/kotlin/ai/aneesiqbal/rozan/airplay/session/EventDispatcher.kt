package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.pairing.CodeDisplay
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Delivers [ReceiverEvents] on one dedicated thread, in the order they were raised.
 *
 * Receiver threads raise events while holding session locks or from media threads that a teardown is
 * waiting for; delivering them here means a callback can call back into the receiver (for example
 * [ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver.disconnectAll]) without deadlocking, and a slow
 * or throwing callback cannot stall or kill a connection. The queue holds at most [QUEUE_CAPACITY]
 * events; beyond that events are dropped and logged.
 */
internal class EventDispatcher(
    private val events: ReceiverEvents,
    private val log: ReceiverLog,
) {
    @Volatile private var thread: Thread? = null

    private val executor =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_CAPACITY)) { task ->
            Thread(task, "airplay-events").also {
                it.isDaemon = true
                thread = it
            }
        }

    /** See [ReceiverEvents.onConnectionStarted]. */
    fun connectionStarted(client: ClientInfo) = post { events.onConnectionStarted(client) }

    /** See [ReceiverEvents.onConnectionEnded]. */
    fun connectionEnded(client: ClientInfo) = post { events.onConnectionEnded(client) }

    /** See [ReceiverEvents.onPinRequired]. */
    fun pinRequired(
        pin: String,
        client: ClientInfo,
    ) = post { events.onPinRequired(pin, client) }

    /** See [ReceiverEvents.onPinFinished]. */
    fun pinFinished(
        client: ClientInfo,
        result: PinResult,
    ) = post { events.onPinFinished(client, result) }

    /** See [ReceiverEvents.onMirroringStarted]. */
    fun mirroringStarted(client: ClientInfo) = post { events.onMirroringStarted(client) }

    /** See [ReceiverEvents.onMirroringStopped]. */
    fun mirroringStopped(client: ClientInfo) = post { events.onMirroringStopped(client) }

    /** See [ReceiverEvents.onAudioStarted]. */
    fun audioStarted(client: ClientInfo) = post { events.onAudioStarted(client) }

    /** See [ReceiverEvents.onAudioStopped]. */
    fun audioStopped(client: ClientInfo) = post { events.onAudioStopped(client) }

    /** See [ReceiverEvents.onNowPlaying]. */
    fun nowPlaying(
        client: ClientInfo,
        nowPlaying: NowPlaying?,
    ) = post { events.onNowPlaying(client, nowPlaying) }

    /**
     * Waits up to [timeoutMillis] for every event raised so far to be delivered. Returns at once when
     * called from the event thread itself (a callback), which would otherwise wait for itself.
     */
    fun drain(timeoutMillis: Long = DRAIN_TIMEOUT_MILLIS) {
        if (Thread.currentThread() === thread) return
        val done = CountDownLatch(1)
        if (post { done.countDown() }) done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Delivers what is queued, then stops the thread. */
    fun shutdown() {
        drain()
        executor.shutdown()
    }

    private fun post(action: () -> Unit): Boolean =
        try {
            executor.execute { guarded(log, "event callback") { action() } }
            true
        } catch (e: RejectedExecutionException) {
            log.log(ReceiverLog.Level.WARN, "event dropped: ${e.message ?: "queue full"}")
            false
        }

    companion object {
        /** Most undelivered events. */
        const val QUEUE_CAPACITY = 1024

        private const val DRAIN_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * [CodeDisplay] for the pairing layer, raising [ReceiverEvents.onPinRequired] and
 * [ReceiverEvents.onPinFinished]. It remembers the sender name shown with each code so the finish event
 * carries the same [ClientInfo]; at most [MAX_PROMPTS] prompts are remembered.
 */
internal class EventCodeDisplay(
    private val dispatcher: EventDispatcher,
) : CodeDisplay {
    private val shown = LinkedHashMap<InetAddress, ClientInfo>()

    override fun show(
        code: String,
        client: InetAddress,
        clientName: String?,
    ) {
        val info = ClientInfo(client.hostAddress, ClientText.clean(clientName), null)
        synchronized(shown) {
            shown.remove(client)
            if (shown.size >= MAX_PROMPTS) shown.remove(shown.keys.first())
            shown[client] = info
        }
        dispatcher.pinRequired(code, info)
    }

    override fun finish(
        client: InetAddress,
        result: PinResult,
    ) {
        val info = synchronized(shown) { shown.remove(client) } ?: ClientInfo(client.hostAddress, null, null)
        dispatcher.pinFinished(info, result)
    }

    companion object {
        /** Most prompts remembered at once. */
        const val MAX_PROMPTS = 16
    }
}
