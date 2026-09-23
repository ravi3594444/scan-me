package com.constrivo.drop.core.data

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-G2 "10,000 rows scroll smoothly": with 10,000 transfers, a History page newest first, at any depth, takes well
 * under 100 ms, and the query plan walks the `transfer_by_start` index without sorting.
 */
class HistoryPerformanceTest {
    private val rows = 10_000

    private fun DropData.seed() {
        val random = Random(10_000)
        val peers = (0 until 20).map { n -> DeviceIds.of(SeededCrypto(1), identityKey(n)) }
        database.transaction {
            for ((n, peer) in peers.withIndex()) {
                database.deviceQueries.insertPeer(peer, identityKey(n), "Peer $n", "phone", T0)
            }
            for (n in 0 until rows) {
                val started = T0 - random.nextLong(365 * DAY)
                database.transferQueries.insert(
                    id = transferId(n).toDb(),
                    peerDeviceId = peers[n % peers.size],
                    direction = if (n % 2 == 0) "send" else "receive",
                    status = "offered",
                    startedAt = started,
                    bytesTotal = random.nextLong(1, 5_000_000_000),
                    fileCount = random.nextLong(1, 500),
                    mimeHistogram = "image/*=3,application/pdf=1",
                )
                if (n % 3 == 0) {
                    database.transferQueries.finish(
                        status = "done",
                        finishedAt = started + 60_000,
                        bytesDone = 0,
                        transport = "p2p",
                        band = "5",
                        avgSpeedBps = 40_000_000,
                        hintCodes = "band24",
                        failedFiles = 0,
                        id = transferId(n).toDb(),
                    )
                }
            }
        }
    }

    /** Best of five runs after a warm-up, in milliseconds: the query cost, without JIT and class loading. Inline, so [block] may suspend. */
    private inline fun bestOf(block: () -> Unit): Double {
        block()
        return (0 until 5).minOf {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        }
    }

    @Test
    fun tenThousandRowsPageNewestFirstInWellUnder100Ms() =
        runTest {
            val data = openTestData()
            data.seed()
            assertEquals(listOf(rows.toLong()), data.driver.longs("SELECT count(*) FROM transfer"))

            val first = data.transfers.historyPage(null, 50)
            assertEquals(50, first.transfers.size)
            val all = data.driver.longs("SELECT started_at FROM transfer ORDER BY started_at DESC, id DESC LIMIT 50")
            assertEquals(all, first.transfers.map { it.startedAtMillis })

            // A cursor 9,950 rows deep.
            var cursor = first.next!!
            repeat(198) { cursor = data.transfers.historyPage(cursor, 50).next!! }
            val deep = data.transfers.historyPage(cursor, 50)
            assertEquals(50, deep.transfers.size)
            assertEquals(null, deep.next, "the last page")

            val firstMs = bestOf { data.transfers.historyPage(null, 50) }
            val deepMs = bestOf { data.transfers.historyPage(cursor, 50) }
            println("history page of 50 from $rows rows: first ${"%.2f".format(firstMs)} ms, deep ${"%.2f".format(deepMs)} ms")
            assertTrue(firstMs < 100, "first page took $firstMs ms")
            assertTrue(deepMs < 100, "deep page took $deepMs ms")
        }

    @Test
    fun historyQueriesUseTheIndexWithoutSorting() =
        runTest {
            val data = openTestData()
            data.seed()
            val statements =
                listOf(
                    "SELECT * FROM transfer_row ORDER BY started_at DESC, id DESC LIMIT 51",
                    "SELECT * FROM transfer_row WHERE started_at <= $T0 AND (started_at < $T0 OR id < 'abc') ORDER BY started_at DESC, id DESC LIMIT 51",
                )
            for (sql in statements) {
                val plan =
                    data.driver
                        .executeQuery(
                            null,
                            "EXPLAIN QUERY PLAN $sql",
                            { cursor ->
                                val out = ArrayList<String>()
                                while (cursor.next().value) out += cursor.getString(3).orEmpty()
                                QueryResult.Value(out)
                            },
                            0,
                        ).value
                        .joinToString(" | ")
                assertTrue("transfer_by_start" in plan, "plan does not use the index: $plan")
                assertFalse("TEMP B-TREE" in plan, "plan sorts: $plan")
            }
        }
}
