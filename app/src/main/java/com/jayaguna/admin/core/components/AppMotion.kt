package com.jayaguna.admin.core.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics

private val tabOrder = listOf("dashboard", "products", "partners", "portfolio", "activity")

/** Higher tab indexes move the viewport left; lower indexes move it right. */
private fun tabDirection(from: String, to: String): Int {
    val previous = tabOrder.indexOf(from)
    val next = tabOrder.indexOf(to)
    return if (previous < 0 || next < 0) 0 else next.compareTo(previous)
}

/** Keys identify pages throughout interruptions, including a quick A -> B -> A. */
@Composable
fun AnimatedCatalogPage(pageKey: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    var lastRequestedPage by remember { mutableStateOf(pageKey) }
    // Transition.currentState can still be an older page when a user taps
    // again. Direction follows the last selected tab, including interruptions.
    val direction = remember(pageKey) { tabDirection(lastRequestedPage, pageKey) }
    SideEffect { lastRequestedPage = pageKey }
    Box(modifier.fillMaxSize().clipToBounds().testTag("catalog-page")) {
        AnimatedContent(
            targetState = pageKey,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it },
            label = "catalog-tab-slide",
            transitionSpec = {
                if (direction == 0) {
                    (EnterTransition.None togetherWith ExitTransition.None).using(null)
                } else {
                    val towards = if (direction > 0) AnimatedContentTransitionScope.SlideDirection.Left
                        else AnimatedContentTransitionScope.SlideDirection.Right
                    (slideIntoContainer(towards, tween(280, easing = FastOutSlowInEasing)) togetherWith
                        slideOutOfContainer(towards, tween(280, easing = FastOutSlowInEasing))).using(null)
                }
            },
        ) { visiblePage ->
            // Outgoing pages remain drawn for the slide but cannot receive
            // input or expose stale controls to accessibility/test searches.
            val inactive = if (visiblePage == pageKey) Modifier else Modifier
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            Box(Modifier.fillMaxSize().testTag("catalog-page:$visiblePage")) {
                Box(Modifier.fillMaxSize().then(inactive)) { content(visiblePage) }
            }
        }
    }
}
