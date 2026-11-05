package com.jayaguna.admin.core.security

import com.jayaguna.admin.data.model.AccessIdentity

class AccessNotApprovedException(val identity: AccessIdentity) : Exception("Akun Google belum mendapatkan izin akses.")
