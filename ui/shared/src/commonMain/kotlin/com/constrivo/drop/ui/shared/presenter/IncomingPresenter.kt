package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.IncomingCardUi
import com.constrivo.drop.ui.shared.model.IncomingOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The receiver's answers to an Offer (the app layer forwards them to the engine). */
interface IncomingActions {
    /** Accept; [alwaysAccept] turns on auto-accept for this (trusted) sender (F‑D2). */
    fun accept(
        offerId: String,
        alwaysAccept: Boolean,
    )

    fun decline(offerId: String)

    /** "Yes, it matches": the user confirmed the six-digit code (F‑B3); the engine stores the trust. */
    fun confirmCode(offerId: String)

    /** The 30 s countdown ran out (design §5.1; the engine sends `Decline{timeout}`). */
    fun timedOut(offerId: String)
}

/**
 * The incoming card (F‑D1, F‑B3, design §5.1): shows the oldest pending Offer with its 30 s countdown, the SAS row for a
 * first-time sender as soon as the code exists, and "Always accept", which is available only for a trusted sender or
 * after the code was confirmed (no trust without the SAS). When the countdown ends the card is withdrawn and
 * [IncomingActions.timedOut] fires once.
 *
 * The countdown ticks once a second on [clock] (the bar animates linearly between ticks); main-thread confined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingPresenter(
    scope: CoroutineScope,
    offers: Flow<List<IncomingOffer>>,
    private val actions: IncomingActions,
    private val clock: MonotonicClock,
) {
    private data class Choice(
        val alwaysAccept: Boolean = false,
        val sasConfirmed: Boolean = false,
    )

    private val choices = MutableStateFlow<Map<String, Choice>>(emptyMap())
    private val answered = MutableStateFlow<Set<String>>(emptySet())

    private val current: Flow<IncomingOffer?> =
        combine(offers, answered) { list, done ->
            list.filter { it.id !in done }.minByOrNull { it.arrivedAtMillis }
        }

    val state: StateFlow<IncomingCardUi?> =
        current
            .flatMapLatest { offer -> if (offer == null) flowOf(null) else countdown(offer) }
            .let { cards -> combine(cards, choices) { card, c -> card?.let { apply(it, c[it.offerId] ?: Choice()) } } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun onAlwaysAcceptChanged(checked: Boolean) {
        val card = state.value ?: return
        if (!card.canAlwaysAccept) return
        update(card.offerId) { it.copy(alwaysAccept = checked) }
    }

    fun onSasConfirmed() {
        val card = state.value ?: return
        if (card.sas == null || card.sasConfirmed) return
        update(card.offerId) { it.copy(sasConfirmed = true) }
        actions.confirmCode(card.offerId)
    }

    fun onAccept() {
        val card = pending() ?: return
        finish(card.offerId)
        actions.accept(card.offerId, card.alwaysAccept && card.canAlwaysAccept)
    }

    fun onDecline() {
        val card = pending() ?: return
        finish(card.offerId)
        actions.decline(card.offerId)
    }

    /** The card on screen unless it was already answered (a double tap answers once). */
    private fun pending(): IncomingCardUi? = state.value?.takeIf { it.offerId !in answered.value }

    private fun countdown(offer: IncomingOffer): Flow<IncomingCardUi?> =
        flow {
            while (true) {
                val left = offer.timeoutMillis - (clock.elapsedMillis() - offer.arrivedAtMillis)
                if (left <= 0) {
                    emit(null)
                    if (offer.id !in answered.value) {
                        actions.timedOut(offer.id)
                        finish(offer.id)
                    }
                    return@flow
                }
                emit(card(offer, left))
                delay((left - 1) % TICK_MILLIS + 1)
            }
        }

    private fun card(
        offer: IncomingOffer,
        left: Long,
    ): IncomingCardUi {
        val previews = offer.previews.take(IncomingCardUi.MAX_PREVIEWS)
        return IncomingCardUi(
            offerId = offer.id,
            senderName = offer.senderName,
            senderInitials = Avatars.initials(offer.senderName),
            senderAvatarHash = Avatars.hash(offer.senderKey),
            senderAvatar = offer.senderAvatar,
            senderPlatform = offer.senderPlatform,
            trusted = offer.trusted,
            summary = offer.summary,
            totalBytes = offer.totalBytes,
            previews = previews,
            morePreviews = (offer.summary.count - previews.size).coerceAtLeast(0),
            sas = if (offer.trusted) null else offer.sas,
            sasConfirmed = false,
            alwaysAccept = false,
            canAlwaysAccept = offer.trusted,
            remainingMillis = left,
            remainingFraction = (left.toFloat() / offer.timeoutMillis).coerceIn(0f, 1f),
        )
    }

    private fun apply(
        card: IncomingCardUi,
        choice: Choice,
    ): IncomingCardUi {
        val confirmed = choice.sasConfirmed && card.sas != null
        val canAlways = card.trusted || confirmed
        return card.copy(sasConfirmed = confirmed, canAlwaysAccept = canAlways, alwaysAccept = choice.alwaysAccept && canAlways)
    }

    private fun update(
        offerId: String,
        change: (Choice) -> Choice,
    ) {
        choices.update { it + (offerId to change(it[offerId] ?: Choice())) }
    }

    private fun finish(offerId: String) {
        answered.update { it + offerId }
        choices.update { it - offerId }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
