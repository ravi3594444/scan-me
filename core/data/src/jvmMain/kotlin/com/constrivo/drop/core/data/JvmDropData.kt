package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Opens the database file at [path] (or an in-memory database when null) with the JVM defaults: the SQLite JDBC
 * driver ([JdbcSqliteDriverFactory]), `Dispatchers.IO`, JCA crypto, the system clock and time zone. The desktop
 * apps call this (with `libs.sqldelight.sqlite.driver` on their classpath, see [JdbcSqliteDriverFactory]); Android
 * builds its own [SqlDriverFactory] and calls [DropData.open].
 */
fun DropData.Companion.openJvm(
    path: String?,
    secretCipher: SecretFieldCipher = SecretFieldCipher.PLAINTEXT,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    crypto: CryptoProvider = JcaCryptoProvider(),
    clock: WallClock = SystemWallClock,
    calendar: LocalCalendar = SystemZoneCalendar,
    firstDayOfWeek: Weekday = Weekday.MONDAY,
): DropData =
    open(
        driverFactory = if (path == null) JdbcSqliteDriverFactory.inMemory() else JdbcSqliteDriverFactory.file(path),
        dispatcher = dispatcher,
        crypto = crypto,
        clock = clock,
        calendar = calendar,
        secretCipher = secretCipher,
        firstDayOfWeek = firstDayOfWeek,
    )
