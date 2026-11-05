package com.jayaguna.admin.core.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/** Exact anchor; accessibility's scroll-axis value is only an estimated distance. */
val LazyListScrollPosition = SemanticsPropertyKey<String>("LazyListScrollPosition")

fun Modifier.scrollPositionSemantics(state: LazyListState): Modifier = semantics {
    this[LazyListScrollPosition] = "${state.firstVisibleItemIndex}:${state.firstVisibleItemScrollOffset}"
}
