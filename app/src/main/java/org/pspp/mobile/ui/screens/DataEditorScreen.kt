package org.pspp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pspp.mobile.R
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.model.MeasureLevel
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.PsppVariable
import org.pspp.mobile.model.VariableType
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.Emerald400
import org.pspp.mobile.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataEditorScreen(
    isNativeJniLoaded: Boolean = false,
    dataset: PsppDataset,
    isVariableView: Boolean,
    onToggleViewMode: (Boolean) -> Unit,
    onUpdateCell: (rowIdx: Int, colIdx: Int, newValue: String) -> Unit,
    onAddRow: () -> Unit,
    onDeleteRow: (Int) -> Unit,
    onAddVariable: (PsppVariable) -> Unit,
    onUpdateVariable: (Int, PsppVariable) -> Unit,
    onDeleteVariable: (Int) -> Unit,
    onSyncToSyntax: () -> Unit,
    onExportSavSaf: () -> Unit,
    onExportCsvSaf: () -> Unit
) {
    var editingCell by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var editingVarIdx by remember { mutableIntStateOf(-1) }
    var showAddVarDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("data_editor_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!isNativeJniLoaded) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("approximation_mode_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = PsppBridge.APPROXIMATION_BANNER,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = PsppBridge.APPROXIMATION_BANNER,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Top Header & Mode Switcher
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isVariableView,
                            onClick = { onToggleViewMode(false) },
                            label = { Text("${stringResource(R.string.data_view_tab)} (${dataset.caseCount})") },
                            leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                            modifier = Modifier.testTag("data_view_tab_button")
                        )
                        FilterChip(
                            selected = isVariableView,
                            onClick = { onToggleViewMode(true) },
                            label = { Text("${stringResource(R.string.variable_view_tab)} (${dataset.variableCount})") },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            modifier = Modifier.testTag("variable_view_tab_button")
                        )
                    }

                    Text(
                        text = "${dataset.name}.sav${if (dataset.modified) " *" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Cyan400
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = onAddRow,
                        label = { Text(stringResource(R.string.add_case)) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier.testTag("add_case_button")
                    )
                    AssistChip(
                        onClick = { showAddVarDialog = true },
                        label = { Text(stringResource(R.string.add_variable)) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier.testTag("add_variable_button")
                    )
                    AssistChip(
                        onClick = onSyncToSyntax,
                        label = { Text(stringResource(R.string.sync_to_syntax)) },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                        modifier = Modifier.testTag("sync_to_syntax_button")
                    )
                    AssistChip(
                        onClick = onExportSavSaf,
                        label = { Text(stringResource(R.string.export_sav)) },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        modifier = Modifier.testTag("export_sav_button")
                    )
                    AssistChip(
                        onClick = onExportCsvSaf,
                        label = { Text(stringResource(R.string.export_csv)) },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        modifier = Modifier.testTag("export_csv_button")
                    )
                }
            }
        }

        // Main Grid Area
        if (!isVariableView) {
            DataViewGrid(
                dataset = dataset,
                onCellClick = { r, c, v -> editingCell = Triple(r, c, v) },
                modifier = Modifier.weight(1f)
            )
        } else {
            VariableViewGrid(
                dataset = dataset,
                onEditVariable = { idx -> editingVarIdx = idx },
                onDeleteVariable = onDeleteVariable,
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Cell Edit Dialog (Data View)
    editingCell?.let { (rowIdx, colIdx, currentVal) ->
        val variable = dataset.variables.getOrNull(colIdx)
        var textVal by remember(rowIdx, colIdx) { mutableStateOf(currentVal) }
        AlertDialog(
            onDismissRequest = { editingCell = null },
            title = {
                Text("${stringResource(R.string.edit_cell_title)} — Case #${rowIdx + 1}, ${variable?.name ?: ""}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (variable != null && variable.label.isNotBlank()) {
                        Text(variable.label, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = textVal,
                        onValueChange = { textVal = it },
                        label = { Text("Value (${variable?.formatSpec() ?: ""})") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cell_value_input")
                    )
                    if (variable != null && variable.valueLabels.isNotEmpty()) {
                        Text("Defined Value Labels:", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            variable.valueLabels.forEach { (k, lbl) ->
                                AssistChip(
                                    onClick = { textVal = k },
                                    label = { Text("$k = $lbl") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateCell(rowIdx, colIdx, textVal)
                        editingCell = null
                    },
                    modifier = Modifier.testTag("save_cell_button")
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onDeleteRow(rowIdx)
                            editingCell = null
                        }
                    ) {
                        Text(stringResource(R.string.delete_case), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editingCell = null }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        )
    }

    // Variable Edit / Add Dialog (Variable View)
    if (editingVarIdx in dataset.variables.indices) {
        val currentVar = dataset.variables[editingVarIdx]
        VariableAttributeDialog(
            initialVariable = currentVar,
            title = "${stringResource(R.string.edit_variable_title)}: ${currentVar.name}",
            onConfirm = { updated ->
                onUpdateVariable(editingVarIdx, updated)
                editingVarIdx = -1
            },
            onDismiss = { editingVarIdx = -1 }
        )
    }

    if (showAddVarDialog) {
        VariableAttributeDialog(
            initialVariable = PsppVariable(
                name = "var_${dataset.variableCount + 1}",
                type = VariableType.NUMERIC,
                width = 8,
                decimals = 2,
                label = "New Variable",
                measure = MeasureLevel.SCALE
            ),
            title = stringResource(R.string.add_variable),
            onConfirm = { created ->
                onAddVariable(created)
                showAddVarDialog = false
            },
            onDismiss = { showAddVarDialog = false }
        )
    }
}

@Composable
private fun DataViewGrid(
    dataset: PsppDataset,
    onCellClick: (Int, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizScroll = rememberScrollState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizScroll)
        ) {
            // Sticky Header Row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("#", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                dataset.variables.forEach { v ->
                    Column(
                        modifier = Modifier
                            .width(120.dp)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = v.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = Cyan400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${v.formatSpec()} • ${v.measure.displayLabel.first()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(dataset.rows) { rowIdx, row ->
                    Row(
                        modifier = Modifier
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = (rowIdx + 1).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        dataset.variables.forEachIndexed { colIdx, v ->
                            val rawVal = row.getOrNull(colIdx).orEmpty()
                            val valLbl = v.valueLabels[rawVal]
                            Column(
                                modifier = Modifier
                                    .width(120.dp)
                                    .clickable { onCellClick(rowIdx, colIdx, rawVal) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("cell_${rowIdx}_${colIdx}")
                            ) {
                                Text(
                                    text = rawVal.ifEmpty { "." },
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (valLbl != null) {
                                    Text(
                                        text = valLbl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Emerald400,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VariableViewGrid(
    dataset: PsppDataset,
    onEditVariable: (Int) -> Unit,
    onDeleteVariable: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizScroll = rememberScrollState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizScroll)
        ) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("#", 40.dp)
                HeaderCell(stringResource(R.string.var_name), 120.dp)
                HeaderCell(stringResource(R.string.var_type), 85.dp)
                HeaderCell(stringResource(R.string.var_width), 64.dp)
                HeaderCell(stringResource(R.string.var_decimals), 74.dp)
                HeaderCell(stringResource(R.string.var_label), 190.dp)
                HeaderCell(stringResource(R.string.var_values), 170.dp)
                HeaderCell(stringResource(R.string.var_missing), 90.dp)
                HeaderCell(stringResource(R.string.var_measure), 90.dp)
                HeaderCell("Actions", 96.dp)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(dataset.variables) { idx, v ->
                    Row(
                        modifier = Modifier
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            .clickable { onEditVariable(idx) }
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .testTag("variable_row_${v.name}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BodyCell((idx + 1).toString(), 40.dp)
                        BodyCell(v.name, 120.dp, color = Cyan400, bold = true)
                        BodyCell(v.type.displayLabel, 85.dp)
                        BodyCell(v.width.toString(), 64.dp)
                        BodyCell(v.decimals.toString(), 74.dp)
                        BodyCell(v.label.ifBlank { "—" }, 190.dp)
                        val valSummary = v.valueLabels.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "None" }
                        BodyCell(valSummary, 170.dp, color = Emerald400)
                        BodyCell(v.missingValues.joinToString(", ").ifEmpty { "None" }, 90.dp)
                        BodyCell(v.measure.displayLabel, 90.dp)
                        Row(modifier = Modifier.width(96.dp)) {
                            IconButton(onClick = { onEditVariable(idx) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${v.name}")
                            }
                            IconButton(
                                onClick = { onDeleteVariable(idx) },
                                enabled = dataset.variables.size > 1
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete ${v.name}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun BodyCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp),
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableAttributeDialog(
    initialVariable: PsppVariable,
    title: String,
    onConfirm: (PsppVariable) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialVariable.name) }
    var type by remember { mutableStateOf(initialVariable.type) }
    var widthStr by remember { mutableStateOf(initialVariable.width.toString()) }
    var decimalsStr by remember { mutableStateOf(initialVariable.decimals.toString()) }
    var label by remember { mutableStateOf(initialVariable.label) }
    var valuesText by remember {
        mutableStateOf(
            initialVariable.valueLabels.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
    }
    var missingText by remember {
        mutableStateOf(initialVariable.missingValues.joinToString(", "))
    }
    var measure by remember { mutableStateOf(initialVariable.measure) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.var_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.var_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthStr,
                        onValueChange = { widthStr = it },
                        label = { Text(stringResource(R.string.var_width)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = decimalsStr,
                        onValueChange = { decimalsStr = it },
                        label = { Text(stringResource(R.string.var_decimals)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(stringResource(R.string.var_type), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VariableType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.displayLabel) }
                        )
                    }
                }
                Text(stringResource(R.string.var_measure), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeasureLevel.entries.forEach { m ->
                        FilterChip(
                            selected = measure == m,
                            onClick = { measure = m },
                            label = { Text(m.displayLabel) }
                        )
                    }
                }
                OutlinedTextField(
                    value = valuesText,
                    onValueChange = { valuesText = it },
                    label = { Text("Value Labels (e.g. 1=Male, 2=Female)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = missingText,
                    onValueChange = { missingText = it },
                    label = { Text("Missing Values (e.g. -99, 999)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedLabels = valuesText.split(",")
                        .mapNotNull { pair ->
                            val parts = pair.split("=", limit = 2)
                            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                                parts[0].trim() to parts[1].trim()
                            } else null
                        }.toMap()
                    val parsedMissing = missingText.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    onConfirm(
                        PsppVariable(
                            name = name.ifBlank { "var1" },
                            type = type,
                            width = widthStr.toIntOrNull()?.coerceIn(1, 64) ?: 8,
                            decimals = decimalsStr.toIntOrNull()?.coerceIn(0, 6) ?: 2,
                            label = label,
                            valueLabels = parsedLabels,
                            missingValues = parsedMissing,
                            measure = measure
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
