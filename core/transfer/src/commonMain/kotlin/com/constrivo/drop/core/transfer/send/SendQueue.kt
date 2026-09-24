package com.constrivo.drop.core.transfer.send

import com.constrivo.drop.core.protocol.ResumeUnit
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock
import kotlinx.coroutines.channels.Channel

/**
 * The sender's shared work queue (architecture §7.4 "a shared chunk queue feeds all streams"): units to send, each
 * with the byte offset to start from (S1). One producer takes from it in order; every stream then takes the prepared
 * units from the producer as soon as its window allows, which is the work stealing: a fast stream simply takes more.
 *
 * Two lanes: **priority** units (retransmits, the rest of a unit after the Bluetooth hop, units of a stream that died)
 * go first, then the **normal** lane in the global order of `TransferLayout` (bundles first, §7.5). A unit is queued at
 * most once; adding it again keeps the smaller start offset. [replace] swaps in the complete missing set of a `Resume`
 * and bumps [epoch], so units prepared before it can be recognised as stale. Thread-safe.
 */
internal class SendQueue {
    /** A queued unit: [global] is its index in the layout's global order. */
    class Item(
        val unit: ResumeUnit,
        val global: Long,
        val priority: Boolean,
        val epoch: Int,
    )

    private val lock = TransferLock()
    private val priority = ArrayDeque<Item>()
    private val normal = ArrayDeque<Item>()
    private val queued = HashMap<Long, Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    /** Incremented by every [replace]. */
    var epoch: Int = 0
        private set

    val size: Int get() = lock.withLock { queued.size }

    val isEmpty: Boolean get() = lock.withLock { queued.isEmpty() }

    operator fun contains(global: Long): Boolean = lock.withLock { global in queued }

    /** Replaces everything with [units] (global order) in the normal lane; returns the new epoch. */
    fun replace(units: List<Pair<ResumeUnit, Long>>): Int {
        val next =
            lock.withLock {
                priority.clear()
                normal.clear()
                queued.clear()
                epoch++
                for ((unit, global) in units) {
                    if (global in queued) continue
                    val item = Item(unit, global, false, epoch)
                    normal.addLast(item)
                    queued[global] = item
                }
                epoch
            }
        signal.trySend(Unit)
        return next
    }

    /** Adds [unit] to the normal lane (used while building the first queue). */
    fun addNormal(
        unit: ResumeUnit,
        global: Long,
    ) {
        lock.withLock {
            if (global in queued) return@withLock
            val item = Item(unit, global, false, epoch)
            normal.addLast(item)
            queued[global] = item
        }
        signal.trySend(Unit)
    }

    /** Puts [unit] at the front of the priority lane; an already queued copy is replaced, keeping the smaller offset. */
    fun addPriority(
        unit: ResumeUnit,
        global: Long,
    ) {
        lock.withLock {
            val existing = queued[global]
            val from = if (existing != null) minOf(existing.unit.fromOffset, unit.fromOffset) else unit.fromOffset
            val item = Item(ResumeUnit(unit.unit, from), global, true, epoch)
            priority.addFirst(item)
            queued[global] = item
        }
        signal.trySend(Unit)
    }

    /** Removes [global] (it was acknowledged, or released by the receiver); true when it was queued. */
    fun remove(global: Long): Boolean = lock.withLock { queued.remove(global) != null }

    /** The next unit, suspending while the queue is empty; null once [close]d. */
    suspend fun take(): Item? {
        while (true) {
            val item =
                lock.withLock {
                    if (closed) return null
                    poll(priority) ?: poll(normal)
                }
            if (item != null) return item
            signal.receiveCatching().getOrNull() ?: return null
        }
    }

    /** The next unit without waiting, or null. */
    fun poll(): Item? = lock.withLock { if (closed) null else poll(priority) ?: poll(normal) }

    fun close() {
        lock.withLock { closed = true }
        signal.close()
    }

    /** Pops the first live item of [lane]; the caller holds the lock. */
    private fun poll(lane: ArrayDeque<Item>): Item? {
        while (lane.isNotEmpty()) {
            val item = lane.removeFirst()
            if (queued[item.global] === item) {
                queued.remove(item.global)
                return item
            }
        }
        return null
    }
}
