package ai.aneesiqbal.rozan.airplay.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Finds and resolves DNS-SD services with the system's own browser, the way another app on the network would. */
internal object NsdBrowser {
    private const val TIMEOUT_SECONDS = 20L
    private val nsd: NsdManager get() = testContext.getSystemService(NsdManager::class.java)

    /** The first instance of [type] that [matches], or null after a while. */
    fun discover(
        type: String,
        matches: (NsdServiceInfo) -> Boolean,
    ): NsdServiceInfo? {
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val discovery = Discovery { if (matches(it)) found += it }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            return found.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            nsd.stopServiceDiscovery(discovery)
        }
    }

    /**
     * [service] with its port and TXT record. Uses resolveService, deprecated from API 34 in favour of
     * registerServiceInfoCallback, which API 28-33 lack.
     */
    @Suppress("DEPRECATION")
    fun resolve(service: NsdServiceInfo): NsdServiceInfo {
        val result = LinkedBlockingQueue<NsdServiceInfo>()
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) = Unit

                override fun onServiceResolved(info: NsdServiceInfo) {
                    result += info
                }
            },
        )
        return requireNotNull(result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "resolve timed out" }
    }

    /** The TXT record as text. */
    fun NsdServiceInfo.txt(): Map<String, String?> = attributes.mapValues { it.value?.decodeToString() }

    private class Discovery(
        private val onFound: (NsdServiceInfo) -> Unit,
    ) : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = onFound(info)

        override fun onServiceLost(info: NsdServiceInfo) = Unit

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit
    }
}
