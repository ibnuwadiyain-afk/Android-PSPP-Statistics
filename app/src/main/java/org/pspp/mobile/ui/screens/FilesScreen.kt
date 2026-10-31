package org.pspp.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pspp.mobile.R
import org.pspp.mobile.model.CsvImportPreviewState
import org.pspp.mobile.model.RecentFileItem
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.Emerald400
import org.pspp.mobile.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(
    recentFiles: List<RecentFileItem>,
    csvPreview: CsvImportPreviewState?,
    onOpenSafFile: () -> Unit,
    onSaveSavSaf: () -> Unit,
    onSaveCsvSaf: () -> Unit,
    onOpenSampleCsvPreview: () -> Unit,
    onUpdateCsvPreviewOptions: (delimiter: Char, hasHeader: Boolean) -> Unit,
    onApplyCsvImport: () -> Unit,
    onDismissCsvPreview: () -> Unit,
    onOpenRecentFile: (RecentFileItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("files_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.files_title),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        // Storage Access Framework Open / Save Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Storage Access Framework (SAF) & Format I/O",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Supported formats: SPSS/PSPP System File (.sav, .zsav), Portable (.por), Delimited Text (.csv, .tsv, .txt), and PSPP Syntax (.sps).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenSafFile,
                            modifier = Modifier.testTag("saf_open_file_button")
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.open_file_saf))
                        }
                        OutlinedButton(
                            onClick = onSaveSavSaf,
                            modifier = Modifier.testTag("saf_save_sav_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save .sav (FL2 Binary)")
                        }
                        OutlinedButton(
                            onClick = onSaveCsvSaf,
                            modifier = Modifier.testTag("saf_save_csv_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save .csv")
                        }
                        AssistChip(
                            onClick = onOpenSampleCsvPreview,
                            label = { Text("Preview Bundled clinical_trial.csv") },
                            leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                            modifier = Modifier.testTag("preview_sample_csv_button")
                        )
                    }
                }
            }
        }

        // Interactive CSV / Delimited Text Column-Type Preview Card
        if (csvPreview != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("csv_column_preview_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ViewColumn, contentDescription = null, tint = Cyan400)
                            Text(
                                text = "${stringResource(R.string.csv_preview_title)} — ${csvPreview.fileName} (${csvPreview.totalRows} rows)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Delimiter:", style = MaterialTheme.typography.labelLarge)
                            listOf(',' to "Comma (,)", ';' to "Semicolon (;)", '\t' to "Tab (\\t)", '|' to "Pipe (|)").forEach { (delimChar, label) ->
                                FilterChip(
                                    selected = csvPreview.delimiter == delimChar,
                                    onClick = { onUpdateCsvPreviewOptions(delimChar, csvPreview.hasHeader) },
                                    label = { Text(label) }
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = csvPreview.hasHeader,
                                    onCheckedChange = { onUpdateCsvPreviewOptions(csvPreview.delimiter, it) }
                                )
                                Text("First row is variable names", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        csvPreview.columns.forEach { col ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${col.index + 1}. ${col.name}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = Cyan400,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Text(
                                    text = "${col.inferredType.displayLabel} • ${col.inferredMeasure.displayLabel}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Emerald400,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = col.sampleValues.joinToString(", "),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1.5f)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onApplyCsvImport,
                                modifier = Modifier.testTag("confirm_csv_import_button")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.apply_import))
                            }
                            OutlinedButton(onClick = onDismissCsvPreview) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
            }
        }

        // Recent Files Section
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = Cyan400)
                Text(
                    text = stringResource(R.string.recent_files),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        items(recentFiles) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenRecentFile(item) }
                    .testTag("recent_file_${item.name}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Cyan400
                        )
                        Text(
                            text = item.uriOrPath,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${item.format} • ${item.sizeBytes} B",
                        style = MaterialTheme.typography.labelMedium,
                        color = Emerald400
                    )
                }
            }
        }
    }
}
