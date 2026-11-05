package com.jayaguna.admin.features.partners.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jayaguna.admin.core.security.userMessage
import com.jayaguna.admin.core.validation.Validation
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.repository.FirebaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.net.URI
import java.util.UUID

data class PartnerLogoSelection(val uri: String, val name: String)
data class PendingPartnerDraft(val partner: Partner, val requestId: String, val locked: Boolean = false)
data class PartnerBatchState(
    val selected: List<PendingPartnerDraft> = emptyList(),
    val saving: Boolean = false,
    val progress: Float? = null,
    val operation: String = "",
    val error: String? = null,
    val notice: String? = null,
    val canRetry: Boolean = false,
)

internal fun validPartnerLogoUri(value: String): Boolean = value.length in 1..4096 &&
    value.none(Char::isISOControl) && runCatching {
        val uri = URI(value)
        uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank()
    }.getOrDefault(false)

internal interface PartnerBatchGateway {
    suspend fun save(partner: Partner, requestId: String, progress: (Float) -> Unit)
}

/** One selected logo becomes one partner, keeping the existing catalog and website schema. */
class PartnerBatchController internal constructor(private val gateway: PartnerBatchGateway) : ViewModel() {
    constructor(repository: FirebaseRepository) : this(object : PartnerBatchGateway {
        override suspend fun save(partner: Partner, requestId: String, progress: (Float) -> Unit) {
            repository.requireOnline()
            repository.savePartner(partner, requestId, progress)
        }
    })

    private val mutable = MutableStateFlow(PartnerBatchState())
    val state = mutable.asStateFlow()
    private val changes = MutableStateFlow(0)
    val completedChanges = changes.asStateFlow()
    private var uiInteracted = false

    fun selectLogos(logos: List<PartnerLogoSelection>) {
        if (mutable.value.saving) return
        uiInteracted = true
        if (logos.any { !validPartnerLogoUri(it.uri) }) {
            mutable.update { it.copy(error = "Pilih logo melalui pemilih foto perangkat.") }
            return
        }
        val old = mutable.value.selected
        val additions = logos.distinctBy(PartnerLogoSelection::uri).filter { logo -> old.none { it.partner.logo?.storagePath == logo.uri } }
        if (old.size + additions.size > MAX_LOGOS) {
            mutable.update { it.copy(error = "Pilih maksimal $MAX_LOGOS logo dalam satu unggahan.") }
            return
        }
        val drafts = additions.mapIndexed { index, logo ->
            val name = suggestedName(logo.name, old.size + index + 1)
            PendingPartnerDraft(Partner(id = UUID.randomUUID().toString(), name = name,
                logo = Media(storagePath = logo.uri), altText = "Logo $name"), UUID.randomUUID().toString())
        }
        mutable.update { it.copy(selected = old + drafts, error = null, notice = null, canRetry = old.any(PendingPartnerDraft::locked)) }
    }

    fun update(partner: Partner) {
        if (mutable.value.saving) return
        uiInteracted = true
        val existing = mutable.value.selected.firstOrNull { it.partner.id == partner.id } ?: return
        if (existing.locked) {
            mutable.update { it.copy(error = "Data logo yang sudah mulai diunggah dikunci agar retry tidak membuat mitra ganda.", canRetry = true) }
            return
        }
        val edited = existing.partner.copy(name = partner.name, altText = partner.altText, website = partner.website,
            displayOrder = partner.displayOrder, active = partner.active)
        mutable.update { state -> state.copy(selected = state.selected.map { if (it.partner.id == partner.id) it.copy(partner = edited) else it },
            error = null, notice = null, canRetry = state.selected.any(PendingPartnerDraft::locked)) }
    }

    fun removeSelection(id: String) {
        if (mutable.value.saving) return
        uiInteracted = true
        if (mutable.value.selected.firstOrNull { it.partner.id == id }?.locked == true) {
            mutable.update { it.copy(error = "Logo ini sudah mulai diproses. Coba simpan kembali untuk memeriksa hasilnya.", canRetry = true) }
            return
        }
        mutable.update { state ->
            val remaining = state.selected.filterNot { it.partner.id == id }
            state.copy(selected = remaining, error = null, notice = null, canRetry = remaining.any(PendingPartnerDraft::locked))
        }
    }

    /** The view confirms abandonment when the selection contains an uncertain, locked request. */
    fun discardSelection() {
        if (mutable.value.saving) return
        uiInteracted = true
        mutable.value = PartnerBatchState()
    }

    fun uploadSelected() = viewModelScope.launch {
        val current = mutable.value
        if (current.saving || current.selected.isEmpty()) return@launch
        uiInteracted = true
        val batch = try {
            current.selected.map { draft ->
                val normalized = draft.partner.copy(name = draft.partner.name.trim(), altText = draft.partner.altText.trim(), website = draft.partner.website.trim())
                validateDraft(normalized)
                draft.copy(partner = normalized)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(error = error.userMessage(), notice = null, canRetry = it.selected.any(PendingPartnerDraft::locked)) }
            return@launch
        }
        var completed = 0
        var attempted: PendingPartnerDraft? = null
        mutable.update { it.copy(saving = true, progress = 0f, error = null, notice = null, canRetry = false) }
        try {
            for ((index, selected) in batch.withIndex()) {
                attempted = selected
                // Freeze the exact payload before the first request, including a lost-success reply.
                val frozen = selected.copy(locked = true)
                mutable.update { state -> state.copy(selected = state.selected.map { if (it.partner.id == selected.partner.id) frozen else it },
                    operation = "Mengunggah logo ${index + 1} dari ${batch.size}") }
                gateway.save(frozen.partner, frozen.requestId) { value ->
                    mutable.update { it.copy(progress = (index + value.coerceIn(0f, 1f)) / batch.size) }
                }
                completed++
                mutable.update { it.copy(selected = it.selected.filterNot { draft -> draft.partner.id == frozen.partner.id }) }
            }
            mutable.update { it.copy(notice = "$completed mitra berhasil disimpan.", error = null, canRetry = false) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Local decoding/URI failures on a first attempt cannot have committed
            // the catalog. An already-uncertain request must remain frozen even if
            // its local URI becomes unreadable during a later retry.
            val localFailure = error is IllegalArgumentException || error is FileNotFoundException
            val release = attempted?.takeIf { localFailure && !it.locked }
            mutable.update { state -> state.copy(
                selected = if (release == null) state.selected else state.selected.map {
                    if (it.partner.id == release.partner.id) it.copy(locked = false) else it
                },
                error = if (error is FileNotFoundException) "Berkas logo tidak dapat dibaca. Hapus pilihan tersebut lalu pilih ulang logo." else error.userMessage(),
                canRetry = true,
                notice = if (completed > 0) "$completed mitra sudah tersimpan. Coba lagi untuk pilihan yang tersisa." else null) }
        } finally {
            mutable.update { it.copy(saving = false, progress = null, operation = "") }
            if (completed > 0) changes.update { it + completed }
        }
    }

    fun snapshotUiState(): List<String> = PartnerBatchUiStateCodec.encode(mutable.value.selected)

    fun restoreUiState(values: List<String>) {
        if (uiInteracted) return
        uiInteracted = true
        val selected = PartnerBatchUiStateCodec.decode(values) ?: return
        val uncertain = selected.any(PendingPartnerDraft::locked)
        mutable.value = PartnerBatchState(selected = selected, canRetry = uncertain,
            error = if (uncertain) "Unggahan terakhir belum dikonfirmasi. Coba simpan kembali menggunakan pilihan yang sama." else null,
            notice = if (selected.isNotEmpty() && !uncertain) "Pilihan logo dipulihkan. Periksa data, lalu tekan Simpan untuk melanjutkan." else null)
    }

    private fun validateDraft(partner: Partner) {
        Validation.partner(partner)
        require(partner.altText.length <= 250) { "Teks alternatif maksimal 250 karakter." }
        require(partner.displayOrder <= 1_000_000) { "Urutan tampil maksimal 1000000." }
        require(partner.website.length <= 2048 && (partner.website.isBlank() || runCatching {
            val uri = URI(partner.website)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in -1..65535 &&
                partner.website.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#][^\\s<>]*)?"))
        }.getOrDefault(false))) { "Website perusahaan harus berupa alamat HTTPS yang valid." }
        require(partner.version == 0L && !partner.archived && validPartnerLogoUri(partner.logo?.storagePath.orEmpty())) { "Pilihan logo tidak valid. Pilih ulang gambar." }
    }

    private fun suggestedName(raw: String, index: Int): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("(?i)\\.(png|jpe?g|webp)$"), "")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(160)
        return name.takeIf { it.length >= 2 } ?: "Perusahaan mitra $index"
    }

    companion object { const val MAX_LOGOS = 8 }
}
