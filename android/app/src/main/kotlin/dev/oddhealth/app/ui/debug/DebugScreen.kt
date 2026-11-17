package dev.oddhealth.app.ui.debug

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.ListGroup
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.model.DebugState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DebugScreen(
    state: DebugState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.debug_logging)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.m, end = Space.m, top = padding.calculateTopPadding(), bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ListGroup(
                    items = listOf(
                        {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.debug_record)) },
                                supportingContent = { Text(stringResource(R.string.debug_record_body)) },
                                trailingContent = { Switch(checked = state.enabled, onCheckedChange = onToggle) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        },
                        {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.debug_files)) },
                                supportingContent = {
                                    Text(
                                        if (state.files == 0) stringResource(R.string.debug_none)
                                        else stringResource(R.string.debug_files_n, state.files, Formatter.formatShortFileSize(LocalContext.current, state.bytes)),
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        },
                    ),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = onShare, enabled = state.files > 0, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.share_logs))
                    }
                    OutlinedButton(onClick = onDelete, enabled = state.files > 0, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.delete_logs))
                    }
                }
            }
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Row(Modifier.padding(Space.m), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.debug_privacy), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
