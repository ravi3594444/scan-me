package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The JVM driver on real dispatchers: a file database under concurrent use, as the desktop apps run it. */
class JvmDriverTest {
    private val tempDir: File = Files.createTempDirectory("drop-jvm").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun concurrentCallersOnAFileDatabaseAllSucceed() =
        runBlocking {
            val data = DropData.openJvm(File(tempDir, "drop.db").path, calendar = LocalCalendar.UTC)
            data.use {
                val peers = (0 until 8).map { n -> data.devices.recordPeer(identityKey(n), "Peer $n", DevicePlatform.PHONE).id }
                withContext(Dispatchers.Default) {
                    (0 until 400)
                        .map { n ->
                            async {
                                val id = transferId(n)
                                data.transfers.create(NewTransfer(id, peers[n % peers.size], TransferDirection.SEND, 100, 1))
                                data.transferFiles.add(id, listOf(NewTransferFile(0, "f$n", null, 100)))
                                data.transfers.updateProgress(id, 50)
                                data.transferFiles.complete(id, 0, "file:///received/f$n")
                                data.transfers.finish(id, TransferOutcome(TransferStatus.DONE, 100, avgSpeedBytesPerSecond = 1_000))
                                data.settings.set(SettingKeys.NICKNAME, "Writer $n")
                            }
                        }.awaitAll()
                }
                assertEquals(400, data.stats.stats().transferCount)
                assertEquals(40_000, data.stats.observe().first().totalBytes)
                assertEquals(400, data.transfers.historyPage(null, 1000).transfers.size)
                assertEquals(listOf(400L), data.driver.longs("SELECT count(*) FROM transfer_file WHERE status = 'done'"))
            }
        }

    @Test
    fun anInMemoryDatabaseWithTheJvmDefaults() =
        runBlocking {
            DropData.openJvm(null).use { data ->
                data.settings.set(SettingKeys.HAPTICS, false)
                assertEquals(false, data.settings.observe(SettingKeys.HAPTICS).first())
                assertEquals(listOf("memory"), data.driver.column("PRAGMA journal_mode"))
            }
        }

    @Test
    fun factoriesDescribeThemselves() {
        assertEquals("JdbcSqliteDriverFactory(in-memory)", JdbcSqliteDriverFactory.inMemory().toString())
        assertEquals(
            "JdbcSqliteDriverFactory(${File(tempDir, "x.db").path})",
            JdbcSqliteDriverFactory.file(File(tempDir, "x.db").path).toString(),
        )
    }
}
