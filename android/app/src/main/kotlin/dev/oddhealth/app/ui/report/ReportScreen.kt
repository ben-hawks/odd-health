package dev.oddhealth.app.ui.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.ListGroup
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.model.DebugState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportScreen(
    debug: DebugState,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
    onReport: (includeLogs: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var includeLogs by rememberSaveable { mutableStateOf(false) }
    val hasLogs = debug.files > 0
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
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
                Text(stringResource(R.string.report_body), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Space.s))
            }
            item {
                ListGroup(
                    items = listOf(
                        {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.report_include_logs)) },
                                supportingContent = {
                                    Text(
                                        if (hasLogs) stringResource(R.string.report_include_logs_body)
                                        else stringResource(R.string.report_no_logs),
                                    )
                                },
                                trailingContent = {
                                    Switch(checked = includeLogs && hasLogs, onCheckedChange = { includeLogs = it }, enabled = hasLogs)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        },
                        {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.debug_logging)) },
                                supportingContent = { Text(stringResource(if (debug.enabled) R.string.on else R.string.report_logging_off)) },
                                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                modifier = Modifier.clickable(onClick = onOpenDebug),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        },
                    ),
                )
            }
            if (includeLogs && hasLogs) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.report_public_warning), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Button(onClick = { onReport(includeLogs && hasLogs) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.report_open))
                }
            }
            item {
                Text(stringResource(R.string.report_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.s))
            }
        }
    }
}

