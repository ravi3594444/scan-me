package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.WifiCredentials

/**
 * What one device runs after the `Offer` / `Accept` exchange (spec change S5).
 *
 * @property p2pCredentials the Wi-Fi Direct credentials: this device's own when it is the group owner, the peer's when
 *   they were pre-shared, null when they will arrive in the host's `LinkReady`.
 * @property announceCredentials this device is the group owner and the peer does not have the credentials yet, so
 *   they go into its `LinkReady`.
 */
data class LinkAgreement(
    val plan: LadderPlan,
    val p2pCredentials: WifiCredentials?,
    val announceCredentials: Boolean = false,
) {
    init {
        require(!announceCredentials || (plan.groupOwner == Side.LOCAL && p2pCredentials != null)) {
            "only the group owner announces its credentials"
        }
        require(plan.groupOwner != Side.LOCAL || p2pCredentials != null) { "the group owner needs credentials (S5)" }
    }

    /** The runner configuration for this agreement. */
    fun config(
        generationBase: Int = 0,
        persistent: Boolean = false,
        prewarm: Boolean = false,
    ): LadderConfig = LadderConfig(generationBase, p2pCredentials, announceCredentials, persistent, prewarm)
}

/** The receiver's decision: [intent] goes into `Accept.link`, [agreement] is what the receiver runs. */
data class AcceptDecision(
    val intent: LinkIntent?,
    val agreement: LinkAgreement,
)

/**
 * The S5 exchange that makes both devices run the same ladder. The group owner generates the Wi-Fi Direct credentials:
 * when the sender is group owner they travel in `Offer.link_options`, when the receiver is in `Accept.link`, and when
 * the owner was only settled by the `Accept` in its `LinkReady`. The receiver's choice is final.
 *
 * 1. Sender: [offerOptions] from its plan.
 * 2. Receiver: [accept] with its own plan and the offered options; the returned intent goes into `Accept`.
 * 3. Sender: [adopt] with the `Accept`'s intent.
 *
 * Both devices then pass [LinkAgreement.plan] and [LinkAgreement.config] to a [LadderRunner]. The hotspot host is not
 * negotiated: both devices elect it from the same facts (its credentials come from the system and always travel in
 * `LinkReady`, N15).
 */
object LadderNegotiation {
    /**
     * Sender: the `Offer.link_options` for [plan], one per Wi-Fi rung in order. The LAN option carries where this device
     * listens ([lanAddress], [lanPort]) when known; the Wi-Fi Direct option carries [p2pCredentials] when this device is
     * the elected group owner; the hotspot option carries nothing.
     *
     * @throws IllegalArgumentException when this device is the group owner and [p2pCredentials] is null.
     */
    fun offerOptions(
        plan: LadderPlan,
        p2pCredentials: WifiCredentials?,
        lanAddress: String? = null,
        lanPort: Int? = null,
    ): List<LinkOption> {
        require(plan.groupOwner != Side.LOCAL || p2pCredentials != null) { "the group owner offers its credentials (S5)" }
        return plan.candidates.mapNotNull { candidate ->
            when (candidate.mode) {
                LinkMode.LAN -> {
                    LinkOption(LinkKind.LAN, address = lanAddress, port = lanPort)
                }

                LinkMode.P2P, LinkMode.P2P_LEGACY -> {
                    LinkOption(
                        LinkKind.P2P,
                        credentials =
                            p2pCredentials.takeIf {
                                candidate.host ==
                                    Side.LOCAL
                            },
                    )
                }

                LinkMode.HOTSPOT -> {
                    LinkOption(LinkKind.HOTSPOT)
                }

                LinkMode.BLUETOOTH -> {
                    null
                }
            }
        }
    }

    /**
     * Receiver: decides the links from its own [plan] and the sender's [offered] options.
     * - Only rungs the sender offered stay (options of kinds this build does not know are skipped).
     * - Wi-Fi Direct: the receiver's election stands. When it elected the sender but the sender offered no (valid)
     *   credentials, the sender elected the receiver, so their facts differ: the receiver then hosts if it can, and
     *   otherwise the sender hosts and announces its credentials in `LinkReady`.
     * - The intent names the first direct rung (Wi-Fi Direct or hotspot), else the LAN, else null (Bluetooth only), with
     *   the receiver's credentials from [newCredentials] when it hosts the group.
     */
    fun accept(
        plan: LadderPlan,
        offered: List<LinkOption>,
        newCredentials: () -> WifiCredentials,
    ): AcceptDecision {
        val kinds = offered.mapNotNull { it.linkKind }.toSet() + LinkKind.BLUETOOTH
        val senderCredentials = offered.firstOrNull { it.linkKind == LinkKind.P2P }?.credentials?.let(::validOrNull)
        var agreed = LadderPlanner.plan(plan.input, PlanConstraints(kinds = kinds))
        if (agreed.groupOwner == Side.PEER && senderCredentials == null) {
            val receiverHosts = LadderPlanner.plan(plan.input, PlanConstraints(kinds = kinds, groupOwner = Side.LOCAL))
            if (receiverHosts.groupOwner == Side.LOCAL) agreed = receiverHosts
        }
        val credentials =
            when (agreed.groupOwner) {
                Side.LOCAL -> P2pCredentials.requireValidGroup(newCredentials())
                Side.PEER -> senderCredentials
                null -> null
            }
        val first = agreed.candidates.firstOrNull { it.mode.isDirect } ?: agreed.candidate(LinkMode.LAN)
        val intent =
            first?.let {
                LinkIntent(it.kind, credentials = credentials.takeIf { _ -> it.kind == LinkKind.P2P && agreed.groupOwner == Side.LOCAL })
            }
        return AcceptDecision(intent, LinkAgreement(agreed, credentials))
    }

    /**
     * Sender: adopts the receiver's [intent] (`Accept.link`, null when absent).
     * - null or LAN: no direct rung (the LAN, if offered, and Bluetooth stay).
     * - Wi-Fi Direct with credentials: the receiver hosts with them. If they are invalid the rung is dropped.
     * - Wi-Fi Direct without credentials: this device hosts, with [offeredCredentials], or with new ones from
     *   [newCredentials] that it announces in `LinkReady`.
     * - Hotspot: no Wi-Fi Direct rung; the hotspot host follows the shared election.
     */
    fun adopt(
        plan: LadderPlan,
        offeredCredentials: WifiCredentials?,
        intent: LinkIntent?,
        newCredentials: () -> WifiCredentials,
    ): LinkAgreement {
        val base = setOf(LinkKind.LAN, LinkKind.BLUETOOTH)
        return when (intent?.linkKind) {
            LinkKind.P2P -> {
                val receiverCredentials = intent.credentials
                if (receiverCredentials != null) {
                    val valid = validOrNull(receiverCredentials)
                    val kinds = base + LinkKind.HOTSPOT + (if (valid != null) setOf(LinkKind.P2P) else emptySet())
                    val agreed = LadderPlanner.plan(plan.input, PlanConstraints(kinds = kinds, groupOwner = Side.PEER))
                    LinkAgreement(agreed, valid.takeIf { agreed.groupOwner == Side.PEER })
                } else {
                    val agreed =
                        LadderPlanner.plan(
                            plan.input,
                            PlanConstraints(kinds = base + LinkKind.P2P + LinkKind.HOTSPOT, groupOwner = Side.LOCAL),
                        )
                    if (agreed.groupOwner != Side.LOCAL) {
                        LinkAgreement(agreed, null)
                    } else {
                        val offered = offeredCredentials?.let(::validOrNull)
                        val credentials = offered ?: P2pCredentials.requireValidGroup(newCredentials())
                        LinkAgreement(agreed, credentials, announceCredentials = offered == null)
                    }
                }
            }

            LinkKind.HOTSPOT -> {
                LinkAgreement(LadderPlanner.plan(plan.input, PlanConstraints(kinds = base + LinkKind.HOTSPOT)), null)
            }

            else -> {
                LinkAgreement(LadderPlanner.plan(plan.input, PlanConstraints(kinds = base)), null)
            }
        }
    }

    private fun validOrNull(credentials: WifiCredentials): WifiCredentials? =
        try {
            P2pCredentials.requireValidGroup(credentials)
        } catch (_: LinkCredentialsException) {
            null
        }
}
