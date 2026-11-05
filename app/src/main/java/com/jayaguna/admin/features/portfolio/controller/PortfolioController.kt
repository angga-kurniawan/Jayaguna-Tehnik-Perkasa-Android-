package com.jayaguna.admin.features.portfolio.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.jayaguna.admin.core.security.userMessage
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.PortfolioImage
import com.jayaguna.admin.data.repository.FirebaseRepository
import com.jayaguna.admin.data.repository.Page
import com.jayaguna.admin.features.dashboard.controller.LatestListRequestGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingPortfolioImage(val id: String, val uri: String, val requestId: String)

data class PortfolioState(
    val category: String = "residential",
    val items: List<PortfolioImage> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val listError: String? = null,
    val selected: List<PendingPortfolioImage> = emptyList(),
    val saving: Boolean = false,
    val progress: Float? = null,
    val operation: String = "",
    val error: String? = null,
    val notice: String? = null,
    val canRetry: Boolean = false,
    val loaded: Boolean = false,
)

internal interface PortfolioGateway {
    suspend fun list(category: String, cursor: DocumentSnapshot?): Page<PortfolioImage>
    suspend fun save(item: PortfolioImage, requestId: String, progress: (Float) -> Unit)
    suspend fun delete(item: PortfolioImage, requestId: String)
}

class PortfolioController internal constructor(
    private val gateway: PortfolioGateway,
) : ViewModel() {
    constructor(repository: FirebaseRepository) : this(object : PortfolioGateway {
        override suspend fun list(category: String, cursor: DocumentSnapshot?) = repository.portfolio(category, cursor)
        override suspend fun save(item: PortfolioImage, requestId: String, progress: (Float) -> Unit) {
            repository.requireOnline()
            repository.savePortfolio(item, requestId, progress)
        }
        override suspend fun delete(item: PortfolioImage, requestId: String) {
            repository.requireOnline()
            repository.deletePortfolio(item, requestId)
        }
    })

    private val mutable = MutableStateFlow(PortfolioState())
    val state = mutable.asStateFlow()
    // Notices can also describe restored input. Only confirmed writes emit changes.
    private val changes = MutableStateFlow(0)
    val completedChanges = changes.asStateFlow()
    private val lists = LatestListRequestGate(viewModelScope)
    private var cursor: DocumentSnapshot? = null
    private val categoryPages = mutableMapOf<String, Triple<PortfolioState, DocumentSnapshot?, Action?>>()
    private var uiInteracted = false
    private sealed interface Action { val requestId: String
        data class Replace(val item: PortfolioImage, override val requestId: String) : Action
        data class Delete(val item: PortfolioImage, override val requestId: String) : Action
    }
    private var pendingAction: Action? = null

    fun snapshotUiState(): List<String> {
        val action = pendingAction?.let {
            when (it) {
                is Action.Replace -> RestoredPortfolioAction("replace", it.item, it.requestId)
                is Action.Delete -> RestoredPortfolioAction("delete", it.item, it.requestId)
            }
        }
        return PortfolioUiStateCodec.encode(RestoredPortfolioState(mutable.value.category, mutable.value.selected, action))
    }

    /** Restore input only; interrupted network mutations always need an explicit retry. */
    fun restoreUiState(snapshot: List<String>) {
        if (uiInteracted) return
        uiInteracted = true
        val restored = PortfolioUiStateCodec.decode(snapshot) ?: return
        pendingAction = restored.action?.let {
            if (it.kind == "replace") Action.Replace(it.item, it.requestId) else Action.Delete(it.item, it.requestId)
        }
        mutable.value = PortfolioState(category = restored.category, selected = restored.selected,
            canRetry = restored.selected.isNotEmpty() || restored.action != null,
            error = if (restored.action != null) "Perubahan terakhir belum dikonfirmasi. Periksa galeri, lalu coba lagi bila diperlukan." else null,
            notice = if (restored.selected.isNotEmpty()) "Pilihan gambar dipulihkan. Periksa preview, lalu tekan Simpan untuk melanjutkan." else null)
    }

    fun ensureLoaded() {
        if (!mutable.value.loaded && !mutable.value.loading && mutable.value.listError == null) load(true)
    }

    fun load(reset: Boolean = false) {
        uiInteracted = true
        val current = mutable.value
        if (!reset && !current.hasMore) return
        val category = current.category
        val pageCursor = if (reset) null else cursor
        lists.launch("portfolio", reset,
            onStart = {
                // Retain the confirmed gallery/cursor if refreshing loses connectivity.
                mutable.update { it.copy(loading = true, listError = null) }
            },
            fetch = { gateway.list(category, pageCursor) },
            onResult = { page ->
                if (mutable.value.category == category) {
                    cursor = page.cursor
                    mutable.update { it.copy(items = if (reset) page.items else (it.items + page.items).distinctBy(PortfolioImage::id), hasMore = page.hasMore, loaded = true) }
                }
            },
            onError = { error -> if (mutable.value.category == category) mutable.update { it.copy(listError = error.userMessage()) } },
            onFinish = { if (mutable.value.category == category) mutable.update { it.copy(loading = false) } })
    }

    fun selectCategory(category: String) {
        if (category !in setOf("residential", "corporate") || category == mutable.value.category || mutable.value.saving || mutable.value.selected.isNotEmpty()) return
        uiInteracted = true
        categoryPages[mutable.value.category] = Triple(mutable.value.copy(loading = false), cursor, pendingAction)
        val cached = categoryPages[category]
        mutable.value = cached?.first ?: PortfolioState(category = category)
        cursor = cached?.second
        pendingAction = cached?.third
        ensureLoaded()
    }

    fun selectImages(uris: List<String>) {
        if (mutable.value.saving) return
        uiInteracted = true
        val existing = mutable.value.selected
        val additions = uris.distinct().filter { uri -> existing.none { it.uri == uri } }
        if (existing.size + additions.size > 8) {
            mutable.update { it.copy(error = "Pilih maksimal 8 gambar dalam satu unggahan.") }
            return
        }
        pendingAction = null
        mutable.update { it.copy(selected = existing + additions.map { PendingPortfolioImage(UUID.randomUUID().toString(), it, UUID.randomUUID().toString()) }, error = null, notice = null, canRetry = false) }
    }

    fun removeSelection(id: String) {
        uiInteracted = true
        if (!mutable.value.saving) mutable.update { it.copy(selected = it.selected.filterNot { item -> item.id == id }, error = null, canRetry = false) }
    }

    fun discardSelection() {
        if (mutable.value.saving) return
        uiInteracted = true
        pendingAction = null
        mutable.update { it.copy(selected = emptyList(), error = null, canRetry = false, notice = null) }
    }

    fun uploadSelected() = viewModelScope.launch {
        val current = mutable.value
        if (current.saving || current.selected.isEmpty()) return@launch
        val batch = current.selected
        val category = current.category
        var completed = 0
        mutable.update { it.copy(saving = true, progress = 0f, error = null, notice = null, canRetry = false) }
        try {
            for ((index, selected) in batch.withIndex()) {
                mutable.update { it.copy(operation = "Mengunggah gambar ${index + 1} dari ${batch.size}") }
                gateway.save(PortfolioImage(id = selected.id, category = category, image = Media(storagePath = selected.uri)), selected.requestId) { progress ->
                    mutable.update { it.copy(progress = (index + progress.coerceIn(0f, 1f)) / batch.size) }
                }
                completed++
                // Confirmed entries never join a retry of the remaining batch.
                mutable.update { it.copy(selected = it.selected.filterNot { image -> image.id == selected.id }) }
            }
            mutable.update { it.copy(notice = "$completed gambar berhasil disimpan.") }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(error = error.userMessage(), canRetry = true, notice = if (completed > 0) "$completed gambar sudah tersimpan. Pilihan yang tersisa dapat dicoba lagi." else null) }
        } finally {
            mutable.update { it.copy(saving = false, progress = null, operation = "") }
            if (completed > 0) {
                changes.update { it + 1 }
                load(true)
            }
        }
    }

    fun replace(item: PortfolioImage, uri: String) {
        if (mutable.value.saving || mutable.value.selected.isNotEmpty()) return
        uiInteracted = true
        val replacement = item.copy(image = Media(storagePath = uri))
        val previous = pendingAction as? Action.Replace
        pendingAction = if (previous?.item == replacement) previous else Action.Replace(replacement, UUID.randomUUID().toString())
        runAction()
    }

    fun delete(item: PortfolioImage) {
        if (mutable.value.saving || mutable.value.selected.isNotEmpty()) return
        uiInteracted = true
        val previous = pendingAction as? Action.Delete
        pendingAction = if (previous?.item == item) previous else Action.Delete(item, UUID.randomUUID().toString())
        runAction()
    }

    fun retry() {
        if (mutable.value.selected.isNotEmpty()) uploadSelected() else runAction()
    }

    private fun runAction() = viewModelScope.launch {
        val action = pendingAction ?: return@launch
        if (mutable.value.saving) return@launch
        mutable.update { it.copy(saving = true, progress = null, error = null, notice = null, canRetry = false, operation = if (action is Action.Delete) "Menghapus gambar" else "Mengganti gambar") }
        try {
            when (action) {
                is Action.Replace -> gateway.save(action.item, action.requestId) { progress -> mutable.update { it.copy(progress = progress) } }
                is Action.Delete -> gateway.delete(action.item, action.requestId)
            }
            pendingAction = null
            mutable.update { it.copy(notice = if (action is Action.Delete) "Gambar berhasil dihapus." else "Gambar berhasil diganti.") }
            changes.update { it + 1 }
            load(true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(error = error.userMessage(), canRetry = true) }
        } finally { mutable.update { it.copy(saving = false, progress = null, operation = "") } }
    }

}
