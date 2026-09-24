package com.constrivo.drop.ui.desktop

import com.constrivo.drop.platform.desktop.OwnerOnlyFiles
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.HexFormat

/**
 * One app per user (architecture §10.2 "single-instance guard"): two copies would both bind the control port, announce
 * twice and fight over the database. The first copy holds an exclusive lock on `instance.lock` and listens on a
 * loopback port; a second copy finds the lock taken, asks the first to show its window, and exits.
 *
 * The port and a random token are in `instance.lock.port` next to the lock, readable by the owner only, so another user
 * of the machine cannot raise this user's window. The listener reads one short line per connection with a timeout and
 * ignores anything but `activate <token>`.
 *
 * Quitting takes a few seconds after the window has gone (the node stops, mDNS says goodbye). The app calls
 * [stopAccepting] as soon as its window exits, so a copy started meanwhile finds no one to activate; it then waits for
 * the lock ([acquire]) and becomes the app once the old copy has exited, instead of raising a window that is gone and
 * exiting with nothing shown.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val portFile: Path,
) : AutoCloseable {
    /** What [acquire] found. */
    sealed interface Outcome {
        /** This process is the app; [instance] holds the lock until closed. */
        class Primary(
            val instance: SingleInstance,
        ) : Outcome

        /** Another copy runs; [activated] tells whether it was asked to show its window. */
        data class Secondary(
            val activated: Boolean,
        ) : Outcome
    }

    /** The loopback port of the activation listener. */
    val port: Int get() = server.localPort

    /**
     * Stops answering later copies (the app is quitting): the listener and the port file go, the lock stays until
     * [close]. Idempotent.
     */
    fun stopAccepting() {
        closeQuietly(server)
        try {
            Files.deleteIfExists(portFile)
        } catch (_: IOException) {
            // The next start overwrites it.
        }
    }

    override fun close() {
        stopAccepting()
        try {
            lock.release()
        } catch (_: IOException) {
            // Released with the channel below.
        }
        closeQuietly(channel)
    }

    companion object {
        private const val PORT_FILE_SUFFIX = ".port"
        private const val COMMAND = "activate"
        private const val TOKEN_BYTES = 16
        private const val MAX_LINE = 128
        private const val READ_TIMEOUT_MILLIS = 2_000
        private const val CONNECT_TIMEOUT_MILLIS = 1_000
        private const val PORT_FILE_ATTEMPTS = 20
        private const val PORT_FILE_RETRY_MILLIS = 50L
        private const val LOCK_RETRY_MILLIS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** How long a start waits for a copy that holds the lock without answering (quitting, bounded by its stop). */
        const val EXIT_WAIT_MILLIS: Long = 20_000

        /**
         * Takes the lock at [lockFile], or asks the copy holding it to show itself. A copy that holds the lock but does
         * not answer is quitting ([stopAccepting]) or starting: the lock is tried again for up to [waitForExitMillis],
         * so a start right after Quit becomes the app once the old copy has exited. [onActivate] runs on a background
         * thread each time a later copy starts.
         *
         * @throws IOException when the lock file or the listener cannot be created at all (a read-only data folder).
         */
        fun acquire(
            lockFile: Path,
            waitForExitMillis: Long = EXIT_WAIT_MILLIS,
            onActivate: () -> Unit,
        ): Outcome {
            require(waitForExitMillis >= 0) { "the wait must not be negative" }
            lockFile.parent?.let(OwnerOnlyFiles::createDirectories)
            val portFile = lockFile.resolveSibling(lockFile.fileName.toString() + PORT_FILE_SUFFIX)
            val deadline = System.nanoTime() + waitForExitMillis * NANOS_PER_MILLI
            while (true) {
                val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = tryLock(channel)
                if (lock != null) return primary(channel, lock, portFile, onActivate)
                closeQuietly(channel)
                if (activate(portFile)) return Outcome.Secondary(activated = true)
                if (System.nanoTime() - deadline >= 0) return Outcome.Secondary(activated = false)
                Thread.sleep(LOCK_RETRY_MILLIS)
            }
        }

        /** The lock on [channel], or null when another process (or this JVM) holds it. */
        private fun tryLock(channel: FileChannel): FileLock? =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Held by this very JVM (tests, or a second start inside one process).
                null
            } catch (e: IOException) {
                closeQuietly(channel)
                throw e
            }

        /** This process as the app: the activation listener and its port file next to the lock just taken. */
        private fun primary(
            channel: FileChannel,
            lock: FileLock,
            portFile: Path,
            onActivate: () -> Unit,
        ): Outcome {
            val server =
                try {
                    ServerSocket(0, 0, InetAddress.getLoopbackAddress())
                } catch (e: IOException) {
                    lock.release()
                    closeQuietly(channel)
                    throw e
                }
            val token = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })
            try {
                writePortFile(portFile, server.localPort, token)
            } catch (e: IOException) {
                closeQuietly(server)
                lock.release()
                closeQuietly(channel)
                throw e
            }
            val instance = SingleInstance(channel, lock, server, portFile)
            Thread({ listen(server, token, onActivate) }, "single-instance").apply { isDaemon = true }.start()
            return Outcome.Primary(instance)
        }

        private fun writePortFile(
            portFile: Path,
            port: Int,
            token: String,
        ) {
            val temp = Files.createTempFile(portFile.parent, portFile.fileName.toString(), ".tmp", *OwnerOnlyFiles.fileAttributes())
            try {
                Files.writeString(temp, "$port $token\n", StandardCharsets.US_ASCII)
                OwnerOnlyFiles.restrict(temp)
                try {
                    Files.move(temp, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, portFile, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        /** Reads `<port> <token>` from [portFile]; null when it is missing or malformed. */
        internal fun readPortFile(portFile: Path): Pair<Int, String>? {
            val text =
                try {
                    Files.readString(portFile, StandardCharsets.US_ASCII)
                } catch (_: IOException) {
                    return null
                }
            val parts = text.trim().split(' ')
            if (parts.size != 2) return null
            val port = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            val token = parts[1].takeIf { it.length == TOKEN_BYTES * 2 && it.all(Char::isLetterOrDigit) } ?: return null
            return port to token
        }

        private const val MAX_PORT = 65_535

        /** Asks the running copy to show its window; the port file may be a moment behind the lock, so it retries. */
        private fun activate(portFile: Path): Boolean {
            repeat(PORT_FILE_ATTEMPTS) {
                val (port, token) =
                    readPortFile(portFile) ?: run {
                        Thread.sleep(PORT_FILE_RETRY_MILLIS)
                        return@repeat
                    }
                return try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
                        socket.getOutputStream().write("$COMMAND $token\n".toByteArray(StandardCharsets.US_ASCII))
                        socket.getOutputStream().flush()
                    }
                    true
                } catch (_: IOException) {
                    false
                }
            }
            return false
        }

        private fun listen(
            server: ServerSocket,
            token: String,
            onActivate: () -> Unit,
        ) {
            val expected = "$COMMAND $token"
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketException) {
                        return // closed
                    } catch (_: IOException) {
                        continue
                    }
                socket.use {
                    try {
                        it.soTimeout = READ_TIMEOUT_MILLIS
                        if (readLine(it) == expected) onActivate()
                    } catch (_: SocketTimeoutException) {
                        // A client that says nothing is dropped.
                    } catch (_: IOException) {
                        // Likewise a broken one.
                    }
                }
            }
        }

        /** One line of at most [MAX_LINE] ASCII bytes, without its terminator; null when longer or not terminated. */
        private fun readLine(socket: Socket): String? {
            val input = socket.getInputStream()
            val out = StringBuilder()
            while (out.length <= MAX_LINE) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return out.toString().trimEnd('\r')
                out.append(b.toChar())
            }
            return null
        }

        private fun closeQuietly(closeable: AutoCloseable) {
            try {
                closeable.close()
            } catch (_: Exception) {
                // Nothing to add.
            }
        }
    }
}
