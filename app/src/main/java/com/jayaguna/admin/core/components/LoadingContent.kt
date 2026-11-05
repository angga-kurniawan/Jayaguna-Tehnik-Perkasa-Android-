package com.jayaguna.admin.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A refresh stays visible even when its list is scrolled, without covering any card. */
@Composable
fun RefreshFrame(refreshing: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        content()
        if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp)
            .align(Alignment.TopCenter).testTag("refresh-progress"))
    }
}

/** Read feedback belongs to the section that is loading, without covering its content. */
@Composable
fun LoadingStatus(message: String, detail: String? = null, progress: Float? = null,
                  modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(message, style = MaterialTheme.typography.labelLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null) Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun PlaceholderLine(fraction: Float, prominent: Boolean = false) {
    Box(Modifier.fillMaxWidth(fraction).height(if (prominent) 16.dp else 12.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)))
}

/** Static shapes reserve the same space as real records; they are never fake catalog items. */
@Composable
fun RecordLoadingPlaceholder(modifier: Modifier = Modifier, lines: Int = 3) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(lines.coerceAtLeast(1)) { index ->
                PlaceholderLine(when (index) { 0 -> .55f; 1 -> .38f; else -> .72f - (index % 3) * .12f }, index == 0)
            }
        }
    }
}

@Composable
fun CatalogLoadingPlaceholder(modifier: Modifier = Modifier, showDetails: Boolean = true, fitLogo: Boolean = false) {
    val textScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    OutlinedCard(modifier.fillMaxWidth()) {
        if (showDetails && !fitLogo) Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(144.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
            PlaceholderLine(.72f, prominent = true)
            PlaceholderLine(.45f)
            PlaceholderLine(.55f)
            PlaceholderActions()
        } else {
        Column {
            Box(Modifier.fillMaxWidth().height(160.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center) {
                Box((if (fitLogo) Modifier.size(80.dp) else Modifier.fillMaxSize().padding(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
            }
            if (showDetails) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.height(48.dp * textScale), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceholderLine(.72f, prominent = true)
                    PlaceholderLine(.45f)
                }
                Box(Modifier.fillMaxWidth().height(40.dp * textScale)) { PlaceholderLine(.55f) }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { PlaceholderActions() }
        }
        }
    }
}

@Composable
private fun PlaceholderActions() {
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(16.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)))
        Box(Modifier.weight(1f).height(16.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)))
    }
}
