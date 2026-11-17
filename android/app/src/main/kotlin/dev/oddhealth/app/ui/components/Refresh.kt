package dev.oddhealth.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Pull down to ask the ring for data and sync. Sits below the top app bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RefreshBox(refreshing: Boolean, onRefresh: () -> Unit, top: Dp, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = Modifier.fillMaxSize().padding(top = top),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = state, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter))
        },
        content = { Box(Modifier.fillMaxSize(), content = content) },
    )
}
