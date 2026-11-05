package com.jayaguna.admin.features.dashboard.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.jayaguna.admin.core.security.userMessage
import com.jayaguna.admin.core.validation.Validation
import com.jayaguna.admin.data.model.*
import com.jayaguna.admin.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ListState<T>(val items: List<T> = emptyList(), val busy: Boolean = false, val error: String? = null, val hasMore: Boolean = true, val loaded: Boolean = false)
data class MutationState(val busy: Boolean = false, val progress: Float? = null, val error: String? = null, val saved: Int = 0, val notice: String? = null)
data class DashboardState(val data: Dashboard? = null, val busy: Boolean = false, val error: String? = null)

/** MVC controller: ViewModel only supplies configuration-safe state and a coroutine scope. */
class AdminController(private val repository: FirebaseRepository, private val account: Account) : ViewModel() {
    private val productState = MutableStateFlow(ListState<Product>())
    val products = productState.asStateFlow()
    private val partnerState = MutableStateFlow(ListState<Partner>())
    val partners = partnerState.asStateFlow()
    private val activityState = MutableStateFlow(ListState<AuditEntry>())
    val activity = activityState.asStateFlow()
    private val userState = MutableStateFlow(ListState<AdminUser>())
    val users = userState.asStateFlow()
    private val dashboardState = MutableStateFlow(DashboardState())
    val dashboard = dashboardState.asStateFlow()
    private val mutationState = MutableStateFlow(MutationState())
    val mutation = mutationState.asStateFlow()
    private val deviceState = MutableStateFlow(repository.device.info())
    val device = deviceState.asStateFlow()
    private val driveState=MutableStateFlow(DriveState())
    val drive=driveState.asStateFlow()
    private val cursors = mutableMapOf<String, DocumentSnapshot?>()
    private val listRequests = LatestListRequestGate(viewModelScope)
    private var productFilter = ProductFilter()
    private var activityFilter = ActivityFilter()
    private var uiInteracted = false
    private var dashboardJob: Job? = null
    private var driveChecked = false
    // Reuses the same request after an uncertain network outcome, including rotation.
    private var pendingKeyHash = ""
    private var pendingRequest = ""
    private fun request(key: Any): String {
        val hash = AdminMutationKey.fingerprint(key)
        if (pendingKeyHash != hash) { pendingKeyHash = hash; pendingRequest = UUID.randomUUID().toString() }
        return pendingRequest
    }
    /** UI-only state; authenticated data/cursors are fetched again after process death. */
    fun snapshotUiState(): List<String> = AdminUiStateCodec.encode(productFilter, activityFilter, pendingKeyHash, pendingRequest)
    fun restoreUiState(snapshot: List<String>) {
        if (uiInteracted) return
        AdminUiStateCodec.decode(snapshot)?.let {
            productFilter = it.product; activityFilter = it.activity
            pendingKeyHash = it.pendingKeyHash; pendingRequest = it.pendingRequest
        }
        uiInteracted = true
    }
    fun ensureLoaded(route: String) {
        when (route) {
            "dashboard" -> if (dashboardState.value.data == null && !dashboardState.value.busy && dashboardState.value.error == null) refreshDashboard()
            "products" -> if (!productState.value.loaded && !productState.value.busy && productState.value.error == null) loadProducts(true)
            "partners" -> if (!partnerState.value.loaded && !partnerState.value.busy && partnerState.value.error == null) loadPartners(true)
            "activity" -> if (!activityState.value.loaded && !activityState.value.busy && activityState.value.error == null) loadActivity(true)
            "users" -> if (!userState.value.loaded && !userState.value.busy && userState.value.error == null) loadUsers(true)
        }
    }
    fun refreshDashboard(): Job {
        dashboardJob?.takeIf { it.isActive }?.let { return it }
        uiInteracted = true
        dashboardState.value = dashboardState.value.copy(busy = true, error = null)
        return viewModelScope.launch {
            try { dashboardState.value = DashboardState(repository.dashboard(account)) }
            catch (e: Exception) { if (e is CancellationException) throw e; dashboardState.update { it.copy(busy = false, error = e.userMessage()) } }
        }.also { dashboardJob = it }
    }
    fun loadProducts(reset: Boolean = false): Job {
        val filter = productFilter
        return load("products", productState, reset) { repository.products(it, filter) }
    }
    fun filterProducts(search: String, category: String, status: String) {
        productFilter = ProductFilter(search, category, status)
        cursors.remove("products")
        productState.update { it.copy(items = emptyList(), hasMore = true, loaded = false) }
        loadProducts(true)
    }
    fun loadPartners(reset: Boolean = false) = load("partners", partnerState, reset) { repository.partners(it) }
    fun loadUsers(reset: Boolean = false) { if (account.role == "OWNER") load("users", userState, reset) { repository.users(it) } }
    fun removeAdmin(user: AdminUser) {
        if (account.role != "OWNER" || user.uid == account.uid || user.role != "ADMIN") return
        mutate(listOf("removeAdmin", user.uid)) { requestId ->
            repository.removeAdmin(user, requestId)
            userState.update { it.copy(items = it.items.filterNot { item -> item.uid == user.uid }) }
            loadUsers(true)
            mutationState.update { it.copy(notice = "Akses admin telah dihapus. Cabut juga izin berbagi foldernya melalui Google Drive.") }
        }
    }
    fun loadActivity(reset: Boolean = false): Job {
        val filter = activityFilter
        return load("activity", activityState, reset) { repository.activity(account, it, filter) }
    }
    fun filterActivity(start: String, end: String, uid: String, type: String, action: String) {
        activityFilter = ActivityFilter(start, end, uid, type, action)
        cursors.remove("activity")
        activityState.update { it.copy(items = emptyList(), hasMore = true, loaded = false) }
        loadActivity(true)
    }
    /** Keep the old history visible while ensuring the next visit gets fresh activity. */
    fun invalidateActivity() {
        val inFlight = activityState.value.busy
        activityState.update { it.copy(loaded = false, error = null) }
        // An older in-flight history response must not mark stale results fresh.
        if (inFlight) loadActivity(true)
    }
    private fun <T> load(key: String, state: MutableStateFlow<ListState<T>>, reset: Boolean, fetch: suspend (DocumentSnapshot?) -> Page<T>): Job {
        uiInteracted = true
        if (!reset && !state.value.hasMore) return Job().apply { complete() }
        val cursor = if (reset) null else cursors[key]
        return listRequests.launch(
            key = key,
            replace = reset,
            onStart = {
                // Keep the confirmed list visible while a refresh is pending.
                // Its cursor remains valid if the refresh fails.
                state.update { it.copy(busy = true, error = null) }
            },
            fetch = { fetch(cursor) },
            onResult = { page ->
                cursors[key] = page.cursor
                state.update { ListState(if (reset) page.items else it.items + page.items, hasMore = page.hasMore, loaded = true) }
            },
            onError = { error -> state.update { it.copy(busy = false, error = error.userMessage()) } },
            onFinish = { state.update { it.copy(busy = false) } }
        ) ?: Job().apply { complete() }
    }
    fun clearMutation() { if (!mutationState.value.busy) mutationState.update { it.copy(error = null, notice = null, progress = null) } }
    private fun mutate(key: Any, operation: suspend (String) -> Unit) = viewModelScope.launch {
        if (mutationState.value.busy) return@launch
        mutationState.update { it.copy(busy = true, progress = null, error = null, notice = null) }
        try {
            repository.requireOnline()
            operation(request(key))
            pendingKeyHash = ""
            pendingRequest = ""
            mutationState.update { it.copy(busy = false, progress = null, saved = it.saved + 1) }
            invalidateActivity()
            refreshDashboard()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutationState.update { it.copy(busy = false, progress = null, error = e.userMessage()) }
        }
    }
    fun saveProduct(product: Product) { mutate(product) { id -> Validation.product(product); repository.saveProduct(product, id) { progress -> mutationState.update { it.copy(progress = progress) } }; loadProducts(true) } }
    fun savePartner(partner: Partner) { mutate(partner) { id -> Validation.partner(partner); repository.savePartner(partner, id) { progress -> mutationState.update { it.copy(progress = progress) } }; loadPartners(true) } }
    fun archiveProduct(product: Product) { mutate(listOf("archiveProduct", product.id, product.version)) { repository.archive("product", product.id, product.version, it); loadProducts(true) } }
    fun archivePartner(partner: Partner) { mutate(listOf("archivePartner", partner.id, partner.version)) { repository.archive("partner", partner.id, partner.version, it); loadPartners(true) } }
    fun renameDevice(name: String) { try { repository.device.rename(name); deviceState.value = repository.device.info() } catch (e: Exception) { mutationState.update { it.copy(error = e.userMessage()) } } }
    fun driveStatus() = viewModelScope.launch {
        if (driveState.value.busy) return@launch
        driveChecked = true
        driveState.update {it.copy(busy=true,error=null)}
        try { verifyDrive() }
        catch(e:Exception) {
            if(e is CancellationException)throw e
            driveState.update {it.copy(busy=false,checked=true,connected=false,error=e.userMessage())}
        }
    }
    private suspend fun verifyDrive() {
        val connection = repository.driveStatus()
        driveState.value = DriveState(connected = connection.connected, accountEmail = connection.accountEmail, checked = true)
    }

}
data class DriveState(val busy:Boolean=false,val connected:Boolean=false,val accountEmail:String="",val error:String?=null,val checked:Boolean=false)
