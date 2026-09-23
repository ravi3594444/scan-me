package com.constrivo.drop.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.random.Random

/** 2026-09-23T12:00:00Z, a Wednesday. */
const val T0: Long = 1_790_164_800_000L

const val HOUR: Long = 60L * 60 * 1000
const val DAY: Long = 24 * HOUR

/** A wall clock the test moves by hand. */
class FakeClock(
    var now: Long = T0,
) : WallClock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** JCA crypto with seeded randomness, so AEAD nonces and generated keys repeat run to run. */
class SeededCrypto(
    seed: Int,
    private val delegate: CryptoProvider = JcaCryptoProvider(),
) : CryptoProvider by delegate {
    private val random = Random(seed)

    override fun randomBytes(size: Int): ByteArray = random.nextBytes(size)
}

/** An in-memory [DropData] on the test scheduler: deterministic, single-threaded, virtual time. */
fun TestScope.openTestData(
    clock: WallClock = FakeClock(),
    cipher: SecretFieldCipher = SecretFieldCipher.PLAINTEXT,
    calendar: LocalCalendar = LocalCalendar.UTC,
    crypto: CryptoProvider = SeededCrypto(1),
    firstDayOfWeek: Weekday = Weekday.MONDAY,
): DropData =
    DropData.open(
        driverFactory = JdbcSqliteDriverFactory.inMemory(),
        dispatcher = StandardTestDispatcher(testScheduler),
        crypto = crypto,
        clock = clock,
        calendar = calendar,
        secretCipher = cipher,
        firstDayOfWeek = firstDayOfWeek,
    )

/** Collects [flow] into a list from the background scope, resuming eagerly; read the list after `runCurrent()`. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.collectInto(flow: Flow<T>): MutableList<T> {
    val values = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { values += it } }
    return values
}

fun identityKey(n: Int): ByteArray = Random(1000 + n).nextBytes(32)

fun secret(n: Int): ByteArray = Random(2000 + n).nextBytes(32)

fun transferId(n: Int): TransferId = TransferId(Random(3000 + n).nextBytes(16))

/** Records a peer and returns its device id. */
suspend fun DropData.peer(
    n: Int,
    nickname: String = "Peer $n",
    platform: DevicePlatform = DevicePlatform.PHONE,
    at: Long = T0,
): String = devices.recordPeer(identityKey(n), nickname, platform, at).id

/** Runs [sql] and returns the first column of every row as text (null for SQL NULL). */
fun SqlDriver.column(sql: String): List<String?> =
    executeQuery(
        null,
        sql,
        { cursor ->
            val out = ArrayList<String?>()
            while (cursor.next().value) out += cursor.getString(0)
            QueryResult.Value(out)
        },
        0,
    ).value

fun SqlDriver.longs(sql: String): List<Long?> =
    executeQuery(
        null,
        sql,
        { cursor ->
            val out = ArrayList<Long?>()
            while (cursor.next().value) out += cursor.getLong(0)
            QueryResult.Value(out)
        },
        0,
    ).value

fun SqlDriver.blob(sql: String): ByteArray? =
    executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getBytes(0) else null) },
        0,
    ).value

/**
 * The schema as SQLite stores it: one block per object in creation order, each introduced by a
 * `-- object <type> <name>` line. The golden files under `src/jvmTest/resources/schema/` use this format.
 */
fun SqlDriver.schemaDump(): String =
    executeQuery(
        null,
        "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid",
        { cursor ->
            val out = StringBuilder()
            while (cursor.next().value) {
                out.append(STATEMENT_MARKER).append(cursor.getString(0)).append(' ').append(cursor.getString(1)).append('\n')
                out.append(cursor.getString(2)).append("\n\n")
            }
            QueryResult.Value(out.toString())
        },
        0,
    ).value

const val STATEMENT_MARKER: String = "-- object "

/** The statements of a [schemaDump], in order. */
fun schemaStatements(dump: String): List<String> =
    dump
        .split(STATEMENT_MARKER)
        .map { it.substringAfter('\n').trim() }
        .filter { it.isNotEmpty() }
