package com.jayaguna.admin.data.model

/** Google identity is signed in; catalogue access still requires a server-confirmed grant. */
data class AccessIdentity(
    val uid: String,
    val name: String,
    val email: String,
    val status: String,
    val requestPending: Boolean,
)

data class AccessRequestResult(val message: String, val alreadyPending: Boolean = false)
