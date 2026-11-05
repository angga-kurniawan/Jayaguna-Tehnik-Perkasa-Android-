package com.jayaguna.admin.core.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private data class CatalogDestination(val route: String, val title: String, val icon: ImageVector)

private val catalogDestinations = listOf(
    CatalogDestination("dashboard", "Beranda", Icons.Outlined.Home),
    CatalogDestination("products", "Produk", Icons.Outlined.AcUnit),
    CatalogDestination("partners", "Mitra", Icons.Outlined.Business),
    CatalogDestination("portfolio", "Portofolio", Icons.Outlined.PhotoLibrary),
    CatalogDestination("activity", "Riwayat", Icons.Outlined.History),
)

/** The original light navigation bar, retaining the session navigation callbacks. */
@Composable
fun CatalogBottomBar(
    route: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        catalogDestinations.forEach { destination ->
            val selected = route == destination.route
            val iconScale by animateFloatAsState(if (selected) 1.08f else 1f,
                animationSpec = tween(160), label = "${destination.route}-icon-scale")
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                enabled = enabled,
                modifier = Modifier.testTag("nav-${destination.route}"),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                icon = { Icon(destination.icon, contentDescription = null,
                    modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) },
                label = { Text(destination.title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}
