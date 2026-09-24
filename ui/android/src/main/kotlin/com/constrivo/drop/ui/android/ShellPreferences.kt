package com.constrivo.drop.ui.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** A small string/boolean store, so the classes that keep state here can be tested with a map. */
internal interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String?,
    )

    fun getBoolean(key: String): Boolean

    fun putBoolean(
        key: String,
        value: Boolean,
    )

    /** Keys that start with [prefix]. */
    fun keys(prefix: String): Set<String>

    /** [KeyValueStore] over app-private [SharedPreferences] (excluded from backup and device transfer). */
    class Prefs(
        private val prefs: SharedPreferences,
    ) : KeyValueStore {
        override fun getString(key: String): String? = prefs.getString(key, null)

        override fun putString(
            key: String,
            value: String?,
        ) = prefs.edit { if (value == null) remove(key) else putString(key, value) }

        override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = prefs.edit { putBoolean(key, value) }

        override fun keys(prefix: String): Set<String> = prefs.all.keys.filterTo(HashSet()) { it.startsWith(prefix) }
    }

    /** In-memory store (tests). */
    class InMemory : KeyValueStore {
        private val values = HashMap<String, Any>()

        override fun getString(key: String): String? = values[key] as? String

        override fun putString(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            values[key] = value
        }

        override fun keys(prefix: String): Set<String> = values.keys.filterTo(HashSet()) { it.startsWith(prefix) }
    }
}

/**
 * What the Android shell keeps between launches until the engine's settings repository (core/data, wired by the
 * app-layer work of WP7) takes over: whether onboarding is done, the nickname it chose, and which permissions the user
 * has actively denied before ([PermissionMatrix.status]).
 */
internal class ShellPreferences(
    private val store: KeyValueStore,
) {
    var onboarded: Boolean
        get() = store.getBoolean(KEY_ONBOARDED)
        set(value) = store.putBoolean(KEY_ONBOARDED, value)

    var nickname: String?
        get() = store.getString(KEY_NICKNAME)
        set(value) = store.putString(KEY_NICKNAME, value)

    fun wasDenied(permission: String): Boolean = store.getBoolean(KEY_DENIED + permission)

    fun markDenied(permission: String) = store.putBoolean(KEY_DENIED + permission, true)

    companion object {
        /** The app-private preferences file. */
        const val FILE: String = "drop_shell"

        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_DENIED = "denied."

        /** The app-private store behind the shell's preferences (also used by [DirectShareIds]). */
        fun store(context: Context): KeyValueStore = KeyValueStore.Prefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
