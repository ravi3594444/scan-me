package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.discovery.Visibility

/** A foreground-service type the transfer service declares (`android:foregroundServiceType`, spec change S9). */
enum class ForegroundKind {
    /** `connectedDevice`: the radio session with nearby devices (advertising, scanning, the Bluetooth listener). */
    CONNECTED_DEVICE,

    /** `dataSync`: a transfer runs (Android 15 counts it against the daily limit, [DataSyncQuota]). */
    DATA_SYNC,
}

/** What the transfer service is doing, from the lifecycle's point of view (architecture §10.1, S9). */
enum class ServicePhase {
    /** Nothing needs the service: it leaves the foreground and stops [ServiceLifecycle.lingerMillis] later. */
    IDLE,

    /**
     * The radio session keeps the phone reachable while the app is in the background: visible to nearby devices
     * (onboarded, visibility not Hidden), an interrupted transfer waiting for its peer (S8), or an offer on the card.
     */
    RADIO_SESSION,

    /** A transfer streams, or the browser receive page is up (F-D6). */
    TRANSFERRING,
}

/**
 * What the lifecycle decides from.
 *
 * @property onboarded the user finished onboarding; before that the phone is never visible in the background.
 * @property visibility the visibility in force now (a 10-minute window that ended reads as its fallback).
 * @property dataSyncAllowed false once Android reported the `dataSync` limit spent ([DataSyncQuota.exhausted]).
 * @property nowElapsedMillis elapsed realtime of the decision.
 */
data class ServiceInputs(
    val activity: NodeActivity,
    val onboarded: Boolean,
    val visibility: Visibility,
    val dataSyncAllowed: Boolean,
    val nowElapsedMillis: Long,
)

/**
 * The lifecycle's answer.
 *
 * @property foreground the types to run under; empty: not a foreground service.
 * @property stopAtElapsedMillis when a started service stops itself (60 s after the last transfer ended or the phone
 *   stopped needing the radio session), or null while something needs it. A bound UI keeps the service alive past it;
 *   the service is then destroyed when the UI unbinds.
 */
data class ServiceDecision(
    val phase: ServicePhase,
    val foreground: Set<ForegroundKind>,
    val stopAtElapsedMillis: Long?,
)

/**
 * The transfer service's lifecycle (architecture §10.1, spec change S9, F-E12), as a small state machine the service
 * feeds on every change of its inputs:
 *
 * | Phase | When | Foreground types |
 * | --- | --- | --- |
 * | [ServicePhase.TRANSFERRING] | a transfer is active, or the browser page is up | `connectedDevice` + `dataSync` (`connectedDevice` alone once the `dataSync` limit is spent) |
 * | [ServicePhase.RADIO_SESSION] | onboarded and visibility not Hidden, a transfer parked or reconnecting, or an offer waiting | `connectedDevice` |
 * | [ServicePhase.IDLE] | otherwise | none; stop [lingerMillis] after the later of the last transfer's end and entering this phase |
 *
 * The radio session needs no runtime permission beyond what `connectedDevice` asks of API 34+ (the install-time
 * `CHANGE_WIFI_MULTICAST_STATE` the manifest declares). Not thread-safe: the service calls it on the main thread.
 */
class ServiceLifecycle(
    val lingerMillis: Long = LINGER_MILLIS,
) {
    init {
        require(lingerMillis >= 0) { "linger must not be negative" }
    }

    /** The phase of the last [update]. */
    var phase: ServicePhase = ServicePhase.IDLE
        private set

    private var idleSince: Long? = null

    /** Decides for [inputs]; call it whenever one of them changes, and at the stop time it returned. */
    fun update(inputs: ServiceInputs): ServiceDecision {
        val next = phaseOf(inputs)
        if (next == ServicePhase.IDLE) {
            if (phase != ServicePhase.IDLE || idleSince == null) idleSince = inputs.nowElapsedMillis
        } else {
            idleSince = null
        }
        phase = next
        val foreground =
            when (next) {
                ServicePhase.TRANSFERRING -> {
                    if (inputs.dataSyncAllowed) {
                        setOf(ForegroundKind.CONNECTED_DEVICE, ForegroundKind.DATA_SYNC)
                    } else {
                        setOf(ForegroundKind.CONNECTED_DEVICE)
                    }
                }

                ServicePhase.RADIO_SESSION -> {
                    setOf(ForegroundKind.CONNECTED_DEVICE)
                }

                ServicePhase.IDLE -> {
                    emptySet()
                }
            }
        val stopAt =
            idleSince?.let { since ->
                maxOf(since, inputs.activity.lastEndedAtElapsedMillis ?: Long.MIN_VALUE) + lingerMillis
            }
        return ServiceDecision(next, foreground, stopAt)
    }

    companion object {
        /** Architecture §10.1: the service stops 60 s after the last transfer. */
        const val LINGER_MILLIS: Long = 60_000

        /** The phase [inputs] call for, before any linger. */
        fun phaseOf(inputs: ServiceInputs): ServicePhase {
            val activity = inputs.activity
            return when {
                activity.transferring -> ServicePhase.TRANSFERRING
                activity.parked > 0 || activity.pendingOffers > 0 -> ServicePhase.RADIO_SESSION
                inputs.onboarded && inputs.visibility != Visibility.HIDDEN -> ServicePhase.RADIO_SESSION
                else -> ServicePhase.IDLE
            }
        }
    }
}
