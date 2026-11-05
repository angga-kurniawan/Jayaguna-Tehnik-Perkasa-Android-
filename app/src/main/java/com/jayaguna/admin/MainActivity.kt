package com.jayaguna.admin

import android.os.Bundle
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.LocalMediaRepository
import com.jayaguna.admin.core.components.LocalCatalogImageLoader
import com.jayaguna.admin.core.navigation.AdminApp
import com.jayaguna.admin.core.theme.JayagunaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setOnExitAnimationListener { provider ->
            if (!ValueAnimator.areAnimatorsEnabled()) provider.remove()
            else provider.view.animate().alpha(0f).scaleX(1.02f).scaleY(1.02f)
                .setDuration(180L).withEndAction { provider.remove() }.start()
        }
        setContent {
            JayagunaTheme {
                val repository = (application as AdminApplication).repository
                if (repository == null) {
                    Scaffold { padding -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Hubungkan Firebase", style = MaterialTheme.typography.headlineMedium)
                        Text("Tambahkan google-services.json milik project Firebase Anda ke folder app/, lalu build ulang. Backend, rules, dan owner perlu dikonfigurasi sesuai README.")
                        Text("ID aplikasi: com.jayaguna.admin")
                    } }
                } else {
                    val media = (application as AdminApplication).mediaRepository
                    val imageLoader = (application as AdminApplication).catalogImageLoader
                    CompositionLocalProvider(LocalMediaRepository provides media, LocalCatalogImageLoader provides imageLoader) { AdminApp(repository) }
                }
            }
        }
    }
}
