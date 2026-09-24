package com.constrivo.drop.platform.android.wifi

/**
 * Where the "hosted a 5 GHz group once" fact of capability bit 4 is kept (architecture §5.2, F-A4). WP7e backs it with
 * persistent settings; [InMemoryVerifiedHostStore] is for tests and for a process that does not persist it.
 */
interface VerifiedHostStore {
    /** True once a Wi-Fi Direct group this device hosted came up at 4900 MHz or above. */
    fun isVerified(): Boolean

    fun setVerified(verified: Boolean)
}

/** A [VerifiedHostStore] in memory. Thread-safe. */
class InMemoryVerifiedHostStore(
    initial: Boolean = false,
) : VerifiedHostStore {
    @Volatile private var verified = initial

    override fun isVerified(): Boolean = verified

    override fun setVerified(verified: Boolean) {
        this.verified = verified
    }
}

/**
 * Records capability bit 4 (`CAN_HOST_P2P_5GHZ`, §5.2): after a group this device hosts forms, [recordHosted] checks its
 * measured frequency and, the first time it is 5 GHz or above ([WifiFrequencies.isFiveGhzOrAbove]), persists the fact
 * in [store] and tells capability detection ([publish], wired to `AndroidCapabilityDetector.setVerifiedP2p5GhzHost`),
 * so the next beacon and handshake advertise it and elections prefer this device as a 5 GHz host.
 *
 * A later 2.4 GHz group never clears the bit: it records that the hardware can host 5 GHz, and a 2.4 GHz result is
 * usually the station's channel (N9), which the election weighs separately. Call [restore] at start-up to publish the
 * persisted fact. Store failures are reported to [onError] and never reach the link.
 */
class FiveGhzHostRecorder(
    private val store: VerifiedHostStore,
    private val publish: (Boolean) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    /** Publishes the persisted fact (call once when the radios start). */
    fun restore() {
        val verified =
            try {
                store.isVerified()
            } catch (e: RuntimeException) {
                onError(e)
                false
            }
        publish(verified)
    }

    /**
     * A hosted group formed on [frequencyMhz] (null when unknown). Returns true when this call newly verified 5 GHz
     * hosting.
     */
    fun recordHosted(frequencyMhz: Int?): Boolean {
        if (!WifiFrequencies.isFiveGhzOrAbove(frequencyMhz)) return false
        return try {
            if (store.isVerified()) return false
            store.setVerified(true)
            publish(true)
            true
        } catch (e: RuntimeException) {
            onError(e)
            false
        }
    }
}
