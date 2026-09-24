package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.FilePickerUi
import com.constrivo.drop.ui.shared.model.PickableUi
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The device's media for the Photos tab and, behind the APK flag, installed apps (platform-implemented). */
interface MediaLibrary {
    /** Recent photos and videos, newest first (design §4.1); empty without access. */
    val media: Flow<List<PickedItem>>

    /** Whether the app may read media (Android: `READ_MEDIA_*`, architecture §11). */
    val mediaAccess: Flow<Boolean>

    /** Installed apps as APKs; only collected when [FeatureFlags.apkSharing] is on (decision 8). */
    val apps: Flow<List<PickedItem>>

    companion object {
        val Empty: MediaLibrary =
            object : MediaLibrary {
                override val media: Flow<List<PickedItem>> = flowOf(emptyList())
                override val mediaAccess: Flow<Boolean> = flowOf(true)
                override val apps: Flow<List<PickedItem>> = flowOf(emptyList())
            }
    }
}

/**
 * Feature flags of the shared UI.
 *
 * @property apkSharing the picker's Apps tab (decision 8: off at launch because of Play review risk, PRD §9).
 */
data class FeatureFlags(
    val apkSharing: Boolean = false,
)

/**
 * The file picker sheet (F‑C1, design §4.1): Photos (grid, recent first, multi-select with count badges), Files (the
 * system picker; picked files are selected at once) and, behind [FeatureFlags.apkSharing], Apps. The running total
 * is the sum of every selected item, whatever tab it came from; the Send button is disabled at zero.
 */
class FilePickerPresenter(
    scope: CoroutineScope,
    library: MediaLibrary,
    private val flags: FeatureFlags = FeatureFlags(),
) {
    private data class Session(
        val targetKey: String,
        val targetName: String?,
        val tab: PickerTab = PickerTab.PHOTOS,
        val selected: List<String> = emptyList(),
        val files: List<PickedItem> = emptyList(),
    )

    private val session = MutableStateFlow<Session?>(null)
    private var media: List<PickedItem> = emptyList()
    private var apps: List<PickedItem> = emptyList()

    private val appsFlow: Flow<List<PickedItem>> = if (flags.apkSharing) library.apps else flowOf(emptyList())

    val state: StateFlow<FilePickerUi?> =
        combine(session, library.media, library.mediaAccess, appsFlow) { s, m, access, a ->
            media = m
            apps = a
            s?.let { build(it, m, access, a) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Opens the sheet for the bubble [targetKey] (design §4.1); a previous selection is dropped. */
    fun open(
        targetKey: String,
        targetName: String?,
    ) {
        session.value = Session(targetKey, targetName)
    }

    fun close() {
        session.value = null
    }

    fun selectTab(tab: PickerTab) {
        if (tab == PickerTab.APPS && !flags.apkSharing) return
        session.update { it?.copy(tab = tab) }
    }

    /** Selects or deselects [itemId]; later selections are renumbered. */
    fun toggle(itemId: String) {
        session.update { s ->
            s ?: return@update null
            val known = s.files.any { it.id == itemId } || media.any { it.id == itemId } || apps.any { it.id == itemId }
            when {
                itemId in s.selected -> s.copy(selected = s.selected - itemId)
                known -> s.copy(selected = s.selected + itemId)
                else -> s
            }
        }
    }

    /** Files returned by the system picker (the Files tab): listed and selected. */
    fun addFiles(items: List<PickedItem>) {
        session.update { s ->
            s ?: return@update null
            val existing = s.files.map { it.id }.toSet()
            val fresh = items.distinctBy { it.id }.filter { it.id !in existing }
            s.copy(files = s.files + fresh, selected = s.selected + fresh.map { it.id }.filter { it !in s.selected })
        }
    }

    /** The selection in selection order, for the send; empty when the sheet is closed. */
    fun selection(): AttachedFiles {
        val s = session.value ?: return AttachedFiles(emptyList())
        val byId = (s.files + media + apps).associateBy { it.id }
        return AttachedFiles(s.selected.mapNotNull { byId[it] })
    }

    /** The bubble the sheet is for. */
    val targetKey: String? get() = session.value?.targetKey

    private fun build(
        s: Session,
        media: List<PickedItem>,
        access: Boolean,
        apps: List<PickedItem>,
    ): FilePickerUi {
        val order = HashMap<String, Int>(s.selected.size * 2)
        s.selected.forEachIndexed { i, id -> order[id] = i + 1 }
        val all = (s.files + media + apps).associateBy { it.id }
        val chosen = s.selected.mapNotNull { all[it] }
        return FilePickerUi(
            targetKey = s.targetKey,
            targetName = s.targetName,
            tabs = if (flags.apkSharing) PickerTab.entries.toList() else listOf(PickerTab.PHOTOS, PickerTab.FILES),
            tab = s.tab,
            photos = media.map { PickableUi(it, order[it.id] ?: 0) },
            photosAccess = access,
            files = s.files.map { PickableUi(it, order[it.id] ?: 0) },
            apps = apps.map { PickableUi(it, order[it.id] ?: 0) },
            selectedCount = chosen.size,
            selectedBytes = chosen.sumOf { it.sizeBytes ?: 0L },
        )
    }
}
