package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The TCP control listener (docs/protocol/01 §3.1): one dual-stack socket on the configured port, one
 * thread per connection, at most [ControlLimits.maxConnections] connections and
 * [ControlLimits.maxConnectionsPerAddress] per address (02 §9.1), and a housekeeping thread for timeouts
 * and the connection deadlines ([ControlConnection.overdue]).
 *
 * When an address is at its cap, a new connection from it replaces that address's oldest unauthenticated
 * connection. When the whole table is full, it replaces an unauthenticated connection of the address that
 * has the most, the oldest of those first, so hosts that hold slots lose their own first. Authenticated
 * and streaming connections are never replaced; with nothing to replace, the new connection is closed on
 * accept. Without this, peers that never finish a request could keep every sender out.
 *
 * Housekeeping runs every [ControlLimits.housekeepingIntervalMillis] only while there is something to
 * check: an open connection, or pairing state that expires ([ReceiverCore.needsHousekeeping]). An idle
 * receiver has no timer at all, so its process is not woken until a connection arrives.
 */
internal class ControlServer(
    private val core: ReceiverCore,
) {
    private val serverSocket = ServerSocket()
    private val connections = LinkedHashSet<ControlConnection>()
    private val housekeeping = Executors.newSingleThreadScheduledExecutor(daemonThreads("airplay-housekeeping"))
    private val housekeepingTask = Runnable { runHousekeeping() }
    private var nextId = 0

    // Guarded by housekeepingTask: whether a housekeeping run is scheduled or running.
    private var housekeepingDue = false

    @Volatile private var closed = false

    /** The bound local port. */
    val port: Int get() = serverSocket.localPort

    /**
     * Binds [port] (0 for an ephemeral one) on every local address, IPv4 and IPv6, and starts accepting.
     *
     * @throws IOException when the port cannot be bound.
     */
    fun start(port: Int) {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(port), BACKLOG)
        Thread(::acceptLoop, "airplay-accept").also { it.isDaemon = true }.start()
    }

    /** Schedules the next housekeeping run unless one is already due. */
    private fun scheduleHousekeeping() {
        synchronized(housekeepingTask) {
            if (housekeepingDue || closed) return
            housekeepingDue = true
        }
        try {
            housekeeping.schedule(housekeepingTask, core.limits.housekeepingIntervalMillis, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Closed meanwhile: nothing left to keep.
            core.log(ReceiverLog.Level.DEBUG, "housekeeping not scheduled: ${describe(e)}")
        }
    }

    private fun runHousekeeping() {
        guarded(core.log, "housekeeping") { core.housekeeping() }
        guarded(core.log, "connection deadlines") { closeOverdue() }
        // Cleared before the check, so a connection admitted meanwhile either sees no run due and schedules
        // one itself, or is seen by the check.
        synchronized(housekeepingTask) { housekeepingDue = false }
        if (snapshot().isNotEmpty() || core.needsHousekeeping()) scheduleHousekeeping()
    }

    private fun closeOverdue() {
        val now = System.nanoTime()
        for (connection in snapshot()) connection.overdue(now)?.let(connection::terminate)
    }

    /** Ends every connection's session (firing the stopped events) and closes the connections. */
    fun disconnectAll(reason: String) {
        for (connection in snapshot()) connection.terminate(reason)
    }

    /**
     * Stops accepting, stops housekeeping and closes every connection, waiting up to [CLOSE_WAIT_MILLIS] in
     * all for the connection threads to finish so their last events are raised first. Idempotent.
     */
    fun close() {
        closed = true
        try {
            serverSocket.close()
        } catch (e: IOException) {
            core.log(ReceiverLog.Level.DEBUG, "closing the listener: ${describe(e)}")
        }
        housekeeping.shutdownNow()
        val open = snapshot()
        for (connection in open) connection.terminate("receiver stopped")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS)
        for (connection in open) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0 || !connection.awaitFinished(left)) {
                core.log(ReceiverLog.Level.WARN, "connection threads still running after stop")
                break
            }
        }
    }

    /** The live connections. */
    fun snapshot(): List<ControlConnection> = synchronized(connections) { connections.toList() }

    private fun acceptLoop() {
        while (!closed) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (closed) return
                    core.log(ReceiverLog.Level.WARN, "accept failed: ${describe(e)}")
                    Thread.sleep(ACCEPT_BACKOFF_MILLIS)
                    continue
                }
            admit(socket)
        }
    }

    private fun admit(socket: Socket) {
        val address = socket.inetAddress
        var evicted: ControlConnection? = null
        val connection =
            synchronized(connections) {
                val fromAddress = connections.filter { it.client == address }
                val addressFull = fromAddress.size >= core.limits.maxConnectionsPerAddress
                val tableFull = connections.size >= core.limits.maxConnections
                // Oldest first: the set keeps the order connections were admitted in.
                val victim =
                    when {
                        addressFull -> fromAddress.firstOrNull { it.evictable }
                        tableFull -> evictionCandidate()
                        else -> null
                    }
                when {
                    closed -> {
                        null
                    }

                    (addressFull || tableFull) && victim == null -> {
                        null
                    }

                    else -> {
                        if (victim != null) {
                            connections -= victim
                            evicted = victim
                        }
                        ControlConnection(++nextId, socket, core, ::removed).also { connections += it }
                    }
                }
            }
        evicted?.terminate("replaced by a new connection: too many connections")
        if (connection == null) {
            core.log(ReceiverLog.Level.WARN, "connection refused: too many connections")
            try {
                socket.close()
            } catch (e: IOException) {
                core.log(ReceiverLog.Level.DEBUG, "closing a refused connection: ${describe(e)}")
            }
            return
        }
        Thread(connection::run, "airplay-control-${connection.id}").also { it.isDaemon = true }.start()
        scheduleHousekeeping()
    }

    /**
     * The connection to replace when the table is full: an unauthenticated one from the address with the
     * most connections, the oldest of those; null when every connection is authenticated or streaming.
     * Call with the set locked.
     */
    private fun evictionCandidate(): ControlConnection? {
        val candidates = connections.filter { it.evictable }
        if (candidates.isEmpty()) return null
        val perAddress = connections.groupingBy { it.client }.eachCount()
        val most = candidates.maxOf { perAddress.getValue(it.client) }
        return candidates.first { perAddress.getValue(it.client) == most }
    }

    private fun removed(connection: ControlConnection) {
        synchronized(connections) { connections.remove(connection) }
    }

    companion object {
        /** Longest [close] waits for connection threads. */
        const val CLOSE_WAIT_MILLIS = 2_000L

        private const val BACKLOG = 16
        private const val ACCEPT_BACKOFF_MILLIS = 100L
    }
}
