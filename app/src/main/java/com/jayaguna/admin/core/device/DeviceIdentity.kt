package com.jayaguna.admin.core.device

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.jayaguna.admin.BuildConfig
import com.jayaguna.admin.data.model.DeviceInfo
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
    private val id = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit(commit = true) { putString("id", it) }
    }
    fun info() = DeviceInfo(id, preferences.getString("name", "Perangkat kantor") ?: "Perangkat kantor", Build.BRAND, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME)
    fun rename(name: String) { require(name.trim().length in 1..80) { "Nama perangkat harus 1–80 karakter." }; preferences.edit { putString("name", name.trim()) } }
}
