package dev.oddhealth.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.Space

/** Sections of the About screen: heading and body string resources. */
private val sections = listOf(
    R.string.about_medical_title to R.string.about_medical_body,
    R.string.about_use_title to R.string.about_use_body,
    R.string.about_not_for_title to R.string.about_not_for_body,
    R.string.about_accuracy_title to R.string.about_accuracy_body,
    R.string.about_data_title to R.string.about_data_body,
    R.string.about_hc_title to R.string.about_hc_body,
    R.string.about_ring_title to R.string.about_ring_body,
    R.string.about_affiliation_title to R.string.about_affiliation_body,
    R.string.about_license_title to R.string.about_license_body,
)

/** Disclaimers, intended use, and how data is handled. Also Health Connect's privacy rationale screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(version: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                subtitle = { Text(stringResource(R.string.about_version, version)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
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
            items(sections.size) { i ->
                val (title, body) = sections[i]
                val warning = i == 0
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Shown once, before first use. */
@Composable
fun FirstRunNotice(onAccept: () -> Unit, onMore: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.first_run_title)) },
        text = { Text(stringResource(R.string.first_run_body)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.first_run_accept)) } },
        dismissButton = { TextButton(onClick = onMore) { Text(stringResource(R.string.first_run_more)) } },
    )
}
