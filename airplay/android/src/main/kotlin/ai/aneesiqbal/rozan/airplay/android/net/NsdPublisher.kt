package ai.aneesiqbal.rozan.airplay.android.net

import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus.Advertising
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress

/**
 * Advertises [ServiceRecord]s through the system mDNS responder ([NsdManager]).
 *
 * Registrations are redone when the default network changes or its addresses
 * change, because older responders keep answering for interfaces that are
 * gone. A new set is registered only after the old one is withdrawn, so the
 * receiver never conflicts with its own stale records and gets renamed.
 * A multicast lock is held while anything is published. All state lives on
 * the main thread; [publish] and [unpublish] may be called from any thread.
 */
internal class NsdPublisher(
    context: Context,
    private val onState: (Advertising) -> Unit,
) {
    private val nsd: NsdManager = context.getSystemService(NsdManager::class.java)
    private val connectivity: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val multicast: WifiManager.MulticastLock =
        context
            .getSystemService(WifiManager::class.java)
            .createMulticastLock("rozan:airplay-nsd")
            .apply { setReferenceCounted(false) }
    private val main = Handler(Looper.getMainLooper())

    private var records: List<ServiceRecord> = emptyList()
    private val registrations = ArrayList<Registration>()
    private var withdrawing = 0
    private var failed = false
    private var network: Network? = null
    private var addresses: Set<InetAddress>? = null
    private var watching = false
    private var lastState: Advertising? = null

    /** Publishes [newRecords], replacing what was published before. Any thread. */
    fun publish(newRecords: List<ServiceRecord>) {
        val copy = newRecords.toList()
        main.post { publishOnMain(copy) }
    }

    /** Withdraws everything. Any thread. */
    fun unpublish() {
        main.post { unpublishOnMain() }
    }

    private fun publishOnMain(newRecords: List<ServiceRecord>) {
        if (newRecords.isEmpty()) {
            unpublishOnMain()
            return
        }
        // The same set again (a republish without changes): keep the registrations. Anything else,
        // e.g. a new identity key in the TXT records after a PIN-paired session, is withdrawn first
        // and then registered afresh.
        val pending = registrations.isNotEmpty() || withdrawing > 0
        if (newRecords == records && pending && !failed) return
        records = newRecords
        if (!multicast.isHeld) multicast.acquire()
        watchNetwork()
        withdrawAll()
        registerIfReady()
    }

    private fun unpublishOnMain() {
        records = emptyList()
        withdrawAll()
        stopWatching()
        if (multicast.isHeld) multicast.release()
        updateState()
    }

    private fun registerIfReady() {
        val idle = registrations.isEmpty() && withdrawing == 0
        if (idle && records.isNotEmpty() && network != null) {
            failed = false
            for (record in records) register(record)
        }
        updateState()
    }

    private fun register(record: ServiceRecord) {
        val registration = Registration(record)
        try {
            nsd.registerService(registration.serviceInfo(), NsdManager.PROTOCOL_DNS_SD, registration)
            registrations += registration
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot register ${record.serviceType}", e)
            failed = true
        }
    }

    private fun withdrawAll() {
        for (registration in registrations) {
            registration.withdrawn = true
            try {
                nsd.unregisterService(registration)
                withdrawing++
            } catch (ignored: IllegalArgumentException) {
                // The system already forgot it (registration failed); nothing to wait for.
            }
        }
        registrations.clear()
    }

    private fun onWithdrawn() {
        withdrawing = (withdrawing - 1).coerceAtLeast(0)
        if (withdrawing == 0) registerIfReady()
    }

    private fun updateState() {
        val state =
            when {
                records.isEmpty() -> Advertising.NOT_PUBLISHED
                network == null -> Advertising.WAITING_FOR_NETWORK
                failed -> Advertising.FAILED
                registrations.isNotEmpty() && registrations.all { it.registered } -> Advertising.ADVERTISING
                else -> Advertising.REGISTERING
            }
        if (state != lastState) {
            lastState = state
            onState(state)
        }
    }

    private fun watchNetwork() {
        if (watching) return
        watching = true
        network = connectivity.activeNetwork
        addresses = network?.let { connectivity.getLinkProperties(it) }?.let(::addressesOf)
        connectivity.registerDefaultNetworkCallback(networkCallback, main)
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        connectivity.unregisterNetworkCallback(networkCallback)
        network = null
        addresses = null
    }

    private fun restartForNetworkChange() {
        if (records.isEmpty()) return
        Log.i(TAG, "network changed; registering again")
        withdrawAll()
        registerIfReady()
    }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (available == network) return
                network = available
                addresses = null
                restartForNetworkChange()
            }

            override fun onLost(lost: Network) {
                if (lost != network) return
                network = null
                addresses = null
                withdrawAll()
                updateState()
            }

            override fun onLinkPropertiesChanged(
                changed: Network,
                linkProperties: LinkProperties,
            ) {
                if (changed != network) return
                val now = addressesOf(linkProperties)
                val before = addresses
                addresses = now
                if (before != null && before != now) restartForNetworkChange()
            }
        }

    private inner class Registration(
        private val record: ServiceRecord,
    ) : NsdManager.RegistrationListener {
        var registered = false
        var withdrawn = false

        fun serviceInfo(): NsdServiceInfo {
            val info = NsdServiceInfo()
            info.serviceName = record.instanceName
            info.serviceType = record.serviceType
            info.port = record.port
            for ((key, value) in record.txt) {
                try {
                    info.setAttribute(key, value)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "skipping TXT entry $key", e)
                }
            }
            return info
        }

        override fun onServiceRegistered(info: NsdServiceInfo) {
            main.post {
                if (!withdrawn) {
                    registered = true
                    if (info.serviceName != record.instanceName) {
                        Log.w(TAG, "${record.serviceType} registered as \"${info.serviceName}\" (name conflict)")
                    }
                    updateState()
                }
            }
        }

        override fun onRegistrationFailed(
            info: NsdServiceInfo,
            errorCode: Int,
        ) {
            main.post {
                if (!withdrawn) {
                    Log.w(TAG, "registering ${record.serviceType} failed: $errorCode")
                    registrations -= this
                    failed = true
                    updateState()
                }
            }
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            main.post { onWithdrawn() }
        }

        override fun onUnregistrationFailed(
            info: NsdServiceInfo,
            errorCode: Int,
        ) {
            main.post { onWithdrawn() }
        }
    }

    private companion object {
        const val TAG = "AirPlayNsd"

        fun addressesOf(properties: LinkProperties): Set<InetAddress> =
            properties.linkAddresses.mapTo(HashSet()) { it.address }
    }
}
