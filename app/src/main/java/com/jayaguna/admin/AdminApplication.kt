package com.jayaguna.admin

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.storage.FirebaseStorage
import com.jayaguna.admin.core.device.DeviceIdentity
import com.jayaguna.admin.data.repository.FirebaseRepository
import com.jayaguna.admin.data.repository.MediaRepository
import com.jayaguna.admin.data.remote.BackendApi
import com.jayaguna.admin.data.drive.NativeDriveService
import com.jayaguna.admin.data.drive.DriveAuthorizationManager
import com.jayaguna.admin.data.repository.NativeCatalogWriter
import coil.ImageLoader
import com.jayaguna.admin.core.components.createCatalogImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdminApplication : Application() {
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var repository: FirebaseRepository? = null
        private set
    var mediaRepository: MediaRepository? = null
        private set
    var catalogImageLoader: ImageLoader? = null
        private set
    var driveAuthorization: DriveAuthorizationManager? = null
        private set
    override fun onCreate() {
        super.onCreate()
        val firebaseApp = FirebaseApp.initializeApp(this) ?: return
        val auth = FirebaseAuth.getInstance(firebaseApp)
        val firestore = FirebaseFirestore.getInstance(firebaseApp)
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        val storage = FirebaseStorage.getInstance(firebaseApp)
        val api=BackendApi(auth)
        val writer = NativeCatalogWriter(firestore, auth)
        val authorization = DriveAuthorizationManager(this, auth)
        driveAuthorization = authorization
        val drive = NativeDriveService(writer, authorization)
        val media = MediaRepository(storage, writer, drive, auth, cacheDir)
        mediaRepository = media
        val imageLoader = createCatalogImageLoader(this, media)
        catalogImageLoader = imageLoader
        imageScope.launch {
            // A different Firebase session must never reuse decoded private images.
            media.cacheIdentity.collect { imageLoader.memoryCache?.clear() }
        }
        repository = FirebaseRepository(this, auth, firestore, api, DeviceIdentity(this), drive, writer, media)
    }
}
