package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.discovery.AppIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop app keeps its files (architecture §10.2, design §9):
 *
 * | | Linux (XDG) | Windows | macOS |
 * | --- | --- | --- | --- |
 * | [config] (secrets, window placement) | `$XDG_CONFIG_HOME/drop` or `~/.config/drop` | `%APPDATA%\Drop` | `~/Library/Application Support/Drop` |
 * | [data] (database, partial files) | `$XDG_DATA_HOME/drop` or `~/.local/share/drop` | `%LOCALAPPDATA%\Drop` | `~/Library/Application Support/Drop` |
 * | [cache] | `$XDG_CACHE_HOME/drop` or `~/.cache/drop` | `%LOCALAPPDATA%\Drop\Cache` | `~/Library/Caches/com.constrivo.drop` |
 * | [received] | `~/Received/Drop` | `%USERPROFILE%\Received\Drop` | `~/Received/Drop` |
 *
 * The names come from [AppIdentity], so renaming the app (decision 1) moves them. XDG variables that are empty or
 * relative are ignored, as the XDG Base Directory specification requires; a missing `%APPDATA%` or `%LOCALAPPDATA%`
 * falls back to its usual place under the profile. [received] is the default Received folder; the user's choice
 * (Settings "Save location", `SettingKeys.SAVE_LOCATION`) overrides it through [receivedFolder].
 *
 * Received files of a drop with more than 20 files go into a per-drop subfolder (design §9); the file store does that
 * (`DirectoryFileStore.DEFAULT_SUBFOLDER_THRESHOLD`).
 */
data class AppDirectories(
    val config: Path,
    val data: Path,
    val cache: Path,
    val received: Path,
) {
    /** The SQLite database (architecture §12). */
    val database: Path get() = data.resolve(DATABASE_FILE)

    /** App-private partial files, `<transfer_id>/<file_index>.part` (architecture §7.6). */
    val partials: Path get() = data.resolve(PARTIALS_DIR)

    /** [FileSecretStorage]'s directory: identity seed, advertising secret, database key (N11). */
    val secrets: Path get() = config.resolve(SECRETS_DIR)

    /** The single-instance lock and its activation port (architecture §10.2: single instance guard). */
    val instanceLock: Path get() = data.resolve(INSTANCE_LOCK_FILE)

    /**
     * Creates [config], [data], [cache], [secrets] and [partials] with owner-only permissions where the file system
     * supports them, and [received] with default permissions (the user's own documents).
     *
     * @throws IOException when a directory cannot be created.
     */
    fun ensureCreated() {
        for (dir in listOf(config, data, cache, secrets, partials)) OwnerOnlyFiles.createDirectories(dir)
        Files.createDirectories(received)
    }

    /**
     * The Received folder to use: [saveLocation] (Settings, an absolute path on desktop) when it is a usable absolute
     * path, else [received].
     */
    fun receivedFolder(saveLocation: String?): Path {
        val chosen =
            saveLocation?.takeIf { it.isNotBlank() }?.let {
                try {
                    Paths.get(it)
                } catch (_: InvalidPathException) {
                    null
                }
            }
        return chosen?.takeIf { it.isAbsolute } ?: received
    }

    companion object {
        const val DATABASE_FILE: String = "drop.db"
        const val PARTIALS_DIR: String = "partials"
        const val SECRETS_DIR: String = "secrets"
        const val INSTANCE_LOCK_FILE: String = "instance.lock"

        /** The directories for this JVM's user and OS. */
        fun current(): AppDirectories = forOs(DesktopOs.current, Paths.get(System.getProperty("user.home")), System.getenv())

        /**
         * The directories for [os] and the user whose home is [home], with the environment [env] (pure, for tests).
         *
         * @throws IllegalArgumentException when [home] is not absolute.
         */
        fun forOs(
            os: DesktopOs,
            home: Path,
            env: Map<String, String>,
        ): AppDirectories {
            require(home.isAbsolute) { "the home directory must be an absolute path, got $home" }
            val received = receivedFolderIn(home)
            return when (os) {
                DesktopOs.WINDOWS -> {
                    val roaming = absoluteEnv(env, "APPDATA") ?: home.resolve("AppData").resolve("Roaming")
                    val local = absoluteEnv(env, "LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")
                    val data = local.resolve(AppIdentity.DISPLAY_NAME)
                    AppDirectories(roaming.resolve(AppIdentity.DISPLAY_NAME), data, data.resolve("Cache"), received)
                }

                DesktopOs.MAC -> {
                    val library = home.resolve("Library")
                    val support = library.resolve("Application Support").resolve(AppIdentity.DISPLAY_NAME)
                    AppDirectories(support, support, library.resolve("Caches").resolve(AppIdentity.PACKAGE), received)
                }

                DesktopOs.LINUX, DesktopOs.OTHER -> {
                    val config = absoluteEnv(env, "XDG_CONFIG_HOME") ?: home.resolve(".config")
                    val data = absoluteEnv(env, "XDG_DATA_HOME") ?: home.resolve(".local").resolve("share")
                    val cache = absoluteEnv(env, "XDG_CACHE_HOME") ?: home.resolve(".cache")
                    AppDirectories(
                        config.resolve(AppIdentity.CODE_NAME),
                        data.resolve(AppIdentity.CODE_NAME),
                        cache.resolve(AppIdentity.CODE_NAME),
                        received,
                    )
                }
            }
        }

        /** `~/Received/<App>/` (design §9) under [home], from [AppIdentity.RECEIVED_FOLDER]. */
        fun receivedFolderIn(home: Path): Path =
            AppIdentity.RECEIVED_FOLDER.split('/').filter { it.isNotEmpty() }.fold(home) { dir, part -> dir.resolve(part) }

        /** The value of [name] as an absolute path, or null when it is unset, empty, relative or not a path. */
        private fun absoluteEnv(
            env: Map<String, String>,
            name: String,
        ): Path? {
            val value = env[name]?.takeIf { it.isNotBlank() } ?: return null
            val path =
                try {
                    Paths.get(value)
                } catch (_: InvalidPathException) {
                    return null
                }
            return path.takeIf { it.isAbsolute }
        }
    }
}
