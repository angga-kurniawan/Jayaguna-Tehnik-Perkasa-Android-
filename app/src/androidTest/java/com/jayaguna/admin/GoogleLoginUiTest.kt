package com.jayaguna.admin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jayaguna.admin.core.theme.JayagunaTheme
import com.jayaguna.admin.data.model.AccessIdentity
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.model.AdminUser
import com.jayaguna.admin.data.model.DeviceInfo
import com.jayaguna.admin.features.auth.view.AccountScreen
import com.jayaguna.admin.features.auth.view.LoginScreen
import com.jayaguna.admin.features.users.view.UsersScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only: these tests do not request Google credentials or write access requests. */
@RunWith(AndroidJUnit4::class)
class GoogleLoginUiTest {
    @get:Rule val compose = createComposeRule()
    private val identity = AccessIdentity("fixture-uid", "Admin Fixture", "fixture@example.com", "unapproved", false)

    @Test fun loginOffersGoogleWithoutPasswordOrResetControls() {
        var signIns = 0
        compose.setContent { JayagunaTheme {
            LoginScreen(false, null, null, { signIns++ })
        } }
        compose.onNodeWithTag("login-google").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
        compose.onNodeWithText("Email kerja").assertDoesNotExist()
        compose.onNodeWithText("Kata sandi").assertDoesNotExist()
        compose.onNodeWithText("Lupa kata sandi?").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("login-request-access").assertDoesNotExist()
    }

    @Test fun unapprovedGoogleIdentityCanRequestAccessWithoutSeeingCatalog() {
        var requests = 0
        compose.setContent { JayagunaTheme {
            LoginScreen(false, null, null, {}, identity = identity, onRequestAccess = { requests++ })
        } }
        compose.onNodeWithText(identity.email).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-request-access").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("login-google").assertDoesNotExist()
        compose.onNodeWithText("Dashboard").assertDoesNotExist()
    }

    @Test fun submittingThenPendingRequestDisablesDuplicatesButKeepsStatusRefresh() {
        val submitting = mutableStateOf(true)
        val pending = mutableStateOf(false)
        var requests = 0
        compose.setContent { JayagunaTheme {
            LoginScreen(false, null, null, {}, identity = identity.copy(requestPending = pending.value),
                requestSubmitting = submitting.value, onRequestAccess = { requests++ })
        } }
        compose.onNodeWithTag("login-request-access").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("login-refresh-access").assertIsNotEnabled()
        compose.onNodeWithTag("login-change-account").assertIsNotEnabled()
        compose.runOnIdle { submitting.value = false; pending.value = true }
        compose.onNodeWithTag("login-request-access").assertIsNotEnabled()
        compose.onNodeWithTag("login-refresh-access").assertIsEnabled()
        compose.onNodeWithTag("login-change-account").assertIsEnabled()
        compose.onNodeWithText("Permohonan Anda sudah tersimpan dan menunggu persetujuan pengelola.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun savedSessionFailureHasRetryAndAccountSwitchWithoutAnotherLoginForm() {
        var retries = 0
        var changes = 0
        compose.setContent { JayagunaTheme {
            LoginScreen(false, "Koneksi belum tersedia.", null, {}, canRetrySession = true,
                onRetrySession = { retries++ }, onChangeAccount = { changes++ })
        } }
        compose.onNodeWithText("Koneksi belum tersedia.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-refresh-access").performScrollTo().performClick()
        compose.onNodeWithTag("login-change-account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, changes) }
        compose.onNodeWithTag("login-google").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun googlePickerInProgressCannotStartAnotherLogin() {
        compose.setContent { JayagunaTheme { LoginScreen(true, null, null, {}) } }
        compose.onNodeWithTag("login-google").performScrollTo().assertIsNotEnabled()
    }

    @Test fun accountShowsSignedInDriveAccountWithoutASecondAccountChooser() {
        var checks = 0
        compose.setContent { JayagunaTheme {
            AccountScreen(Account("fixture", "admin@example.com", "Admin", "ADMIN"), DeviceInfo(deviceName = "Perangkat uji"), {}, {},
                driveConnected = true, driveEmail = "company@example.com", onRefreshDrive = { checks++ })
        } }
        compose.onNodeWithText("Terhubung dengan akun Google Anda: company@example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Periksa koneksi Drive").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithText("Pilih akun Google").assertDoesNotExist()
        compose.onNodeWithText("Ganti akun Google").assertDoesNotExist()
    }

    @Test fun cancelledDriveConsentCanBeRetriedFromAccount() {
        var requests = 0
        compose.setContent { JayagunaTheme {
            AccountScreen(Account("fixture", "admin@example.com", "Admin", "ADMIN"), DeviceInfo(deviceName = "Perangkat uji"), {}, {},
                driveError = "Izin Drive belum selesai.", onRefreshDrive = { requests++ })
        } }
        compose.onNodeWithText("Izin Drive belum selesai.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Izinkan Drive").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Pilih akun Google").assertDoesNotExist()
    }

    @Test fun userListWithoutOwnerActionKeepsManualApprovalAndNoRemovalControls() {
        compose.setContent { JayagunaTheme {
            UsersScreen(listOf(AdminUser("fixture", "Admin Fixture", "fixture@example.com")), false, null, {}, ownerUid = "owner")
        } }
        compose.onNodeWithText("Persetujuan oleh pemilik").assertIsDisplayed()
        compose.onNodeWithText("fixture@example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+ Tambah admin").assertDoesNotExist()
        compose.onNodeWithText("Aktifkan akses").assertDoesNotExist()
        compose.onNodeWithText("Nonaktifkan akses").assertDoesNotExist()
        compose.onNodeWithText("Hapus admin").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun ownerCanRemoveOnlyAnotherAdminAndRevokedUsersHaveNoAction() {
        val users = listOf(
            AdminUser("owner", "Pemilik saat ini", "owner@example.com", "ADMIN"),
            AdminUser("another-owner", "Pemilik lain", "another-owner@example.com", "OWNER"),
            AdminUser("admin", "Admin bekerja", "admin@example.com"),
            AdminUser("revoked", "Admin lama", "revoked@example.com", active = false, status = "revoked")
        )
        compose.setContent { JayagunaTheme {
            UsersScreen(users, false, null, {}, ownerUid = "owner", onRemoveAdmin = {})
        } }
        compose.onNodeWithText("owner@example.com").performScrollTo()
        compose.onNodeWithTag("remove-admin:owner").assertDoesNotExist()
        compose.onNodeWithText("another-owner@example.com").performScrollTo()
        compose.onNodeWithTag("remove-admin:another-owner").assertDoesNotExist()
        compose.onNodeWithTag("remove-admin:admin").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("revoked@example.com").performScrollTo()
        compose.onNodeWithTag("remove-admin:revoked").assertDoesNotExist()
        compose.onNodeWithText("ADMIN · Akses dihapus").assertIsDisplayed()
    }

    @Test fun cancellingAdminRemovalLeavesAccessUnchanged() {
        val removed = mutableListOf<AdminUser>()
        val admin = AdminUser("admin", "Admin Fixture", "admin@example.com")
        compose.setContent { JayagunaTheme {
            UsersScreen(listOf(admin), false, null, {}, ownerUid = "owner", onRemoveAdmin = { removed += it })
        } }
        compose.onNodeWithTag("remove-admin:admin").performScrollTo().performClick()
        compose.onNodeWithTag("remove-admin-dialog").assertIsDisplayed()
        compose.onNodeWithText("Akses aplikasi untuk Admin Fixture (admin@example.com) akan dicabut. Riwayat perubahan tetap tersimpan. Akses folder Google Drive perlu dicabut melalui Google Drive.").assertIsDisplayed()
        compose.onNodeWithTag("cancel-remove-admin").performClick()
        compose.onNodeWithTag("remove-admin-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<AdminUser>(), removed) }
    }

    @Test fun confirmingAdminRemovalSendsTheSelectedAccountOnce() {
        val removed = mutableListOf<AdminUser>()
        val first = AdminUser("first", "Admin pertama", "first@example.com")
        val selected = AdminUser("selected", "Admin dipilih", "selected@example.com")
        compose.setContent { JayagunaTheme {
            UsersScreen(listOf(first, selected), false, null, {}, ownerUid = "owner", onRemoveAdmin = { removed += it })
        } }
        compose.onNodeWithTag("remove-admin:selected").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-remove-admin").performClick()
        compose.onNodeWithTag("remove-admin-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(selected), removed) }
    }

    @Test fun aChangedRoleAndBusyStateCannotSubmitAdminRemoval() {
        val busy = mutableStateOf(false)
        val user = mutableStateOf(AdminUser("admin", "Admin Fixture", "admin@example.com"))
        val removed = mutableListOf<AdminUser>()
        compose.setContent { JayagunaTheme {
            UsersScreen(listOf(user.value), busy.value, null, {}, loading = false,
                ownerUid = "owner", onRemoveAdmin = { removed += it })
        } }
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("remove-admin:admin").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithTag("remove-admin:admin").performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("confirm-remove-admin").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; user.value = user.value.copy(role = "OWNER") }
        compose.onNodeWithTag("remove-admin-dialog").assertDoesNotExist()
        compose.onNodeWithTag("remove-admin:admin").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<AdminUser>(), removed) }
    }

    @Test fun revokedAccessNoticeOverridesAnOldPendingRequest() {
        val status = mutableStateOf("inactive")
        compose.setContent { JayagunaTheme {
            LoginScreen(false, null, null, {}, identity = identity.copy(status = status.value, requestPending = true))
        } }
        fun assertRevoked() {
            compose.onNodeWithText("Akses akun ini telah dinonaktifkan. Hubungi pemilik perusahaan.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Permohonan Anda sudah tersimpan dan menunggu persetujuan pengelola.").assertDoesNotExist()
            compose.onNodeWithTag("login-request-access").performScrollTo().assertIsNotEnabled()
        }
        assertRevoked()
        compose.runOnIdle { status.value = "revoked" }
        assertRevoked()
    }
}
