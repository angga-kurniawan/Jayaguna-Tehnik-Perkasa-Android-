package com.jayaguna.admin.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.*
import com.jayaguna.admin.data.repository.FirebaseRepository
import com.jayaguna.admin.features.auth.controller.GoogleSignInCoordinator
import com.jayaguna.admin.features.auth.controller.AuthController
import com.jayaguna.admin.AdminApplication
import com.jayaguna.admin.features.auth.view.*
import com.jayaguna.admin.features.dashboard.controller.AdminController
import com.jayaguna.admin.features.dashboard.view.DashboardScreen
import com.jayaguna.admin.features.products.view.*
import com.jayaguna.admin.features.partners.view.*
import com.jayaguna.admin.features.partners.controller.PartnerBatchController
import com.jayaguna.admin.features.portfolio.controller.PortfolioController
import com.jayaguna.admin.features.portfolio.view.PortfolioScreen
import com.jayaguna.admin.features.activity.view.ActivityScreen
import com.jayaguna.admin.features.users.view.UsersScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

private class ControllerFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

@Composable
fun AdminApp(repository: FirebaseRepository) {
    val auth: AuthController = viewModel(factory = ControllerFactory { AuthController(repository) })
    val state by auth.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val google = remember(context) { GoogleSignInCoordinator(context) }
    val scope = rememberCoroutineScope()
    val driveAuthorization = requireNotNull((context.applicationContext as AdminApplication).driveAuthorization)
    val driveConnection = rememberDriveLoginConnection(state.account, driveAuthorization, repository)
    var googleBusy by remember { mutableStateOf(false) }
    var googleAttempt by remember { mutableLongStateOf(0L) }
    var googleJob by remember { mutableStateOf<Job?>(null) }
    val logout: () -> Unit = {
        googleAttempt++
        googleJob?.cancel()
        googleJob = null
        googleBusy = false
        driveAuthorization.reset()
        auth.logout()
        scope.launch {
            try { google.clearCredentialState() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Firebase is signed out; the explicit Google chooser remains available. */ }
        }
    }
    val account = state.account
    if (account == null) {
        LoginScreen(
            busy = state.loading || googleBusy, error = state.error, notice = state.notice,
            onGoogleSignIn = {
                if (!googleBusy && !state.loading) {
                    val attempt = ++googleAttempt
                    googleBusy = true
                    googleJob = scope.launch {
                        try {
                            val token = google.signIn()
                            if (attempt == googleAttempt) auth.loginWithGoogle(token)
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { if (attempt == googleAttempt) auth.googleSignInFailed(error) }
                        finally { if (attempt == googleAttempt) googleBusy = false }
                    }
                }
            },
            identity = state.identity, requestSubmitting = state.requestSubmitting,
            onRequestAccess = auth::requestAccess, canRetrySession = state.canRetrySession,
            onRetrySession = auth::retrySession, onChangeAccount = logout
        )
    } else key(account.uid) {
        // Scope catalog state to this signed-in session and discard it on logout.
        val controller: AdminController = viewModel(viewModelStoreOwner = auth, key = account.uid, factory = ControllerFactory { AdminController(repository, account) })
        val portfolio: PortfolioController = viewModel(viewModelStoreOwner = auth, key = "portfolio-${account.uid}",
            factory = ControllerFactory { PortfolioController(repository) })
        val partnerBatch: PartnerBatchController = viewModel(viewModelStoreOwner = auth, key = "partner-batch-${account.uid}",
            factory = ControllerFactory { PartnerBatchController(repository) })
        if (rememberWindowInsetsReady()) CatalogApp(account, controller, portfolio, partnerBatch, logout, driveConnection)
        else Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.Center) {
            LoadingNotice("Menyiapkan tampilan…")
        }
    }
}

/** A restored list must not be measured against the larger pre-insets viewport:
 * doing so clamps a near-bottom anchor before the system bars are dispatched. */
@Composable
private fun rememberWindowInsetsReady(): Boolean {
    val view = LocalView.current
    var ready by remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (ViewCompat.getRootWindowInsets(view) != null) ready = true
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
    }
    return ready
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogApp(account: Account, controller: AdminController, portfolioController: PortfolioController,
                       partnerBatchController: PartnerBatchController, onLogout: () -> Unit,
                       driveConnection: DriveLoginConnection) {
    var route by rememberSaveable { mutableStateOf("dashboard") }
    var primaryRoute by rememberSaveable { mutableStateOf("dashboard") }
    var auxiliaryStack by rememberSaveable(stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it.toList() })) { mutableStateOf(emptyList()) }
    var editorKey by rememberSaveable { mutableStateOf("") }
    var product by rememberSaveable(stateSaver = ProductStateSaver) { mutableStateOf(Product()) }
    var partner by rememberSaveable(stateSaver = PartnerStateSaver) { mutableStateOf(Partner()) }
    val screenStates = rememberSaveableStateHolder()
    val adminRestoration = rememberControllerRestoration(controller::snapshotUiState)
    val portfolioRestoration = rememberControllerRestoration(portfolioController::snapshotUiState)
    val partnerBatchRestoration = rememberControllerRestoration(partnerBatchController::snapshotUiState)
    val dashboard by controller.dashboard.collectAsStateWithLifecycle()
    val products by controller.products.collectAsStateWithLifecycle()
    val partners by controller.partners.collectAsStateWithLifecycle()
    val activity by controller.activity.collectAsStateWithLifecycle()
    val users by controller.users.collectAsStateWithLifecycle()
    val mutation by controller.mutation.collectAsStateWithLifecycle()
    val device by controller.device.collectAsStateWithLifecycle()
    val drive = driveConnection.state
    val portfolio by portfolioController.state.collectAsStateWithLifecycle()
    val portfolioChanges by portfolioController.completedChanges.collectAsStateWithLifecycle()
    val partnerBatch by partnerBatchController.state.collectAsStateWithLifecycle()
    val partnerBatchChanges by partnerBatchController.completedChanges.collectAsStateWithLifecycle()
    var seenSaved by rememberSaveable { mutableIntStateOf(0) }
    var seenPortfolioChanges by rememberSaveable { mutableIntStateOf(0) }
    var seenPartnerBatchChanges by rememberSaveable { mutableIntStateOf(0) }
    val editor = route in setOf("productEditor", "partnerEditor", "partnerBatch")
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun clearPageFocus() { focus.clearFocus(force = true); keyboard?.hide() }
    fun navigate(target: String) {
        if (mutation.busy || portfolio.saving || partnerBatch.saving) return
        if (target == route) return
        clearPageFocus()
        if (editor) screenStates.removeState("editor:$editorKey")
        controller.clearMutation()
        when (target) {
            "addProduct" -> { product = Product(); editorKey = UUID.randomUUID().toString(); route = "productEditor" }
            "addPartner" -> { editorKey = UUID.randomUUID().toString(); route = "partnerBatch" }
            "users", "account" -> if (target != "users" || account.role == "OWNER") {
                auxiliaryStack = auxiliaryStack + route
                route = target
            }
            in primaryDestinations -> { primaryRoute = target; auxiliaryStack = emptyList(); route = target }
        }
    }
    fun back() {
        if (mutation.busy || portfolio.saving || partnerBatch.saving) return
        if (auxiliaryStack.isNotEmpty()) {
            route = auxiliaryStack.last()
            auxiliaryStack = auxiliaryStack.dropLast(1)
        } else navigate("dashboard")
    }
    LaunchedEffect(route, controller, portfolioController, partnerBatchController) {
        // Restore only small UI values. Server data and cursors stay in the
        // session ViewModels and are fetched again after process recreation.
        adminRestoration.consume()?.let(controller::restoreUiState)
        portfolioRestoration.consume()?.let(portfolioController::restoreUiState)
        partnerBatchRestoration.consume()?.let(partnerBatchController::restoreUiState)
        controller.ensureLoaded(route)
        if (route == "portfolio") portfolioController.ensureLoaded()
    }
    LaunchedEffect(mutation.saved) {
        if (mutation.saved < seenSaved) seenSaved = mutation.saved // a new process has a new counter
        else if (mutation.saved > seenSaved) {
            seenSaved = mutation.saved
            if (editor) screenStates.removeState("editor:$editorKey")
            if (route == "productEditor") { route = "products"; primaryRoute = "products" }
            if (route == "partnerEditor") { route = "partners"; primaryRoute = "partners" }
        }
    }
    LaunchedEffect(portfolioChanges) {
        if (portfolioChanges < seenPortfolioChanges) seenPortfolioChanges = portfolioChanges
        else if (portfolioChanges > seenPortfolioChanges) {
            seenPortfolioChanges = portfolioChanges
            controller.invalidateActivity()
            controller.refreshDashboard()
        }
    }
    LaunchedEffect(partnerBatchChanges, partnerBatch.saving) {
        if (partnerBatchChanges < seenPartnerBatchChanges) seenPartnerBatchChanges = partnerBatchChanges
        else if (partnerBatchChanges > seenPartnerBatchChanges && !partnerBatch.saving) {
            seenPartnerBatchChanges = partnerBatchChanges
            controller.loadPartners(true)
            controller.invalidateActivity()
            controller.refreshDashboard()
            if (!partnerBatch.saving && partnerBatch.selected.isEmpty() && route == "partnerBatch") {
                screenStates.removeState("editor:$editorKey")
                route = "partners"; primaryRoute = "partners"
            }
        }
    }
    BackHandler(enabled = !editor && route != "dashboard") { back() }
    Scaffold(
        topBar = { if (!editor) TopAppBar(title = {
            when (route) { "account" -> Text("Akun & perangkat"); "users" -> Text("Akses pengguna"); else -> BrandIdentity(compact = true) }
        }, navigationIcon = {
            if (route in setOf("account", "users")) IconButton(onClick = ::back, enabled = !mutation.busy && !portfolio.saving && !partnerBatch.saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali ke halaman sebelumnya") }
        }, actions = {
            if (account.role == "OWNER" && route != "users") TextButton(onClick = { navigate("users") }, enabled = !mutation.busy && !portfolio.saving && !partnerBatch.saving) { Text("Pengguna") }
            if (route != "account") IconButton(onClick = { navigate("account") }, enabled = !mutation.busy && !portfolio.saving && !partnerBatch.saving) { Icon(Icons.Outlined.AccountCircle, "Akun dan perangkat") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        bottomBar = { if (!editor) CatalogBottomBar(if (route in primaryDestinations) route else primaryRoute, !mutation.busy && !portfolio.saving && !partnerBatch.saving, ::navigate) }
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        if (route != "account") {
            if (drive.busy) LoadingStatus("Menghubungkan Google Drive…", "Selesaikan izin Google jika diminta.")
            else if (drive.error != null) ErrorNotice(drive.error, driveConnection.retry)
        }
        if (!editor && mutation.error != null) ErrorNotice(mutation.error) { controller.clearMutation() }
        if (!editor && mutation.notice != null) Text(mutation.notice!!, Modifier.padding(16.dp))
        if (!editor && mutation.busy) LoadingNotice("Menyimpan perubahan", "Tunggu hingga perubahan dikonfirmasi.", mutation.progress)
        AnimatedCatalogPage(if (editor) "$route:$editorKey" else route) { displayedPage ->
        val displayedRoute = displayedPage.substringBefore(':')
        // Keep the existing editor saved-state key while rendering each
        // transition page from its own key instead of the latest selected tab.
        val stateKey = if (displayedRoute in setOf("productEditor", "partnerEditor", "partnerBatch"))
            "editor:${displayedPage.substringAfter(':')}" else displayedPage
        screenStates.SaveableStateProvider(stateKey) { when (displayedRoute) {
            "dashboard" -> DashboardScreen(dashboard.data, dashboard.busy, dashboard.error, { controller.refreshDashboard() }, ::navigate)
            "products" -> ProductsScreen(products.items, products.busy || mutation.busy, products.error, products.hasMore, { controller.loadProducts() }, { controller.loadProducts(true) }, { navigate("addProduct") }, { clearPageFocus(); controller.clearMutation(); product = it; editorKey = UUID.randomUUID().toString(); route = "productEditor" }, controller::archiveProduct, controller::filterProducts, initialReady = products.loaded, loading = products.busy)
            "productEditor" -> ProductEditor(product.takeIf { it.id.isNotBlank() }, mutation.busy, mutation.progress, mutation.error, controller::saveProduct, { navigate("products") })
            "partners" -> PartnersScreen(partners.items, partners.busy || mutation.busy, partners.error, partners.hasMore, { controller.loadPartners() }, { controller.loadPartners(true) }, { navigate("addPartner") }, { clearPageFocus(); controller.clearMutation(); partner = it; editorKey = UUID.randomUUID().toString(); route = "partnerEditor" }, controller::archivePartner, initialReady = partners.loaded, loading = partners.busy)
            "partnerEditor" -> PartnerEditor(partner.takeIf { it.id.isNotBlank() }, mutation.busy, mutation.progress, mutation.error, controller::savePartner, { navigate("partners") })
            "partnerBatch" -> PartnerBatchScreen(partnerBatch, partnerBatchController::selectLogos, partnerBatchController::update,
                partnerBatchController::removeSelection, partnerBatchController::discardSelection, { partnerBatchController.uploadSelected() },
                { navigate("partners") })
            "portfolio" -> PortfolioScreen(portfolio, portfolioController::selectCategory, portfolioController::selectImages,
                portfolioController::removeSelection, portfolioController::discardSelection, { portfolioController.uploadSelected() },
                portfolioController::replace, portfolioController::delete, portfolioController::retry,
                { portfolioController.load(true) }, { portfolioController.load() }, initialReady = portfolio.loaded)
            "activity" -> ActivityScreen(activity.items, activity.busy, activity.error, activity.hasMore, { controller.loadActivity() }, { controller.loadActivity(true) }, controller::filterActivity, owner = account.role == "OWNER", initialReady = activity.loaded)
            "users" -> if (account.role == "OWNER") UsersScreen(users.items, users.busy || mutation.busy, users.error, { controller.loadUsers(true) }, hasMore = users.hasMore, onLoadMore = { controller.loadUsers() }, initialReady = users.loaded, loading = users.busy,
                ownerUid = account.uid, onRemoveAdmin = controller::removeAdmin)
            "account" -> AccountScreen(account, device, controller::renameDevice, onLogout,drive.connected,drive.accountEmail,drive.busy,drive.error,
                driveConnection.retry, initialReady = drive.checked)
        } }
        }
    } }
}

private val primaryDestinations = setOf("dashboard", "products", "partners", "portfolio", "activity")

private class ControllerRestoration(private var restored: List<String>? = null) {
    fun consume(): List<String>? = restored.also { restored = null }
}

/** Save simple controller inputs without serializing repositories, jobs or tokens. */
@Composable
private fun rememberControllerRestoration(snapshot: () -> List<String>): ControllerRestoration =
    rememberSaveable(saver = Saver<ControllerRestoration, List<String>>(
        save = { snapshot() }, restore = { ControllerRestoration(it) }
    )) { ControllerRestoration() }
