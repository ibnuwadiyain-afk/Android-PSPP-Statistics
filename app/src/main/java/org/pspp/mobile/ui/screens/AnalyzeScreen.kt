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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pspp.mobile.R
import org.pspp.mobile.engine.PsppSyntaxGenerator
import org.pspp.mobile.model.AnalysisProcedure
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.TTestMode
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.JetBrainsMonoFontFamily

private data class ProcedureSpec(
    val procedure: AnalysisProcedure,
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector
)

@Composable
fun AnalyzeScreen(
    dataset: PsppDataset,
    selectedProcedure: AnalysisProcedure?,
    onSelectProcedure: (AnalysisProcedure?) -> Unit,
    onRunGeneratedSyntax: (String) -> Unit,
    onSendSyntaxToEditor: (String) -> Unit
) {
    val specs = remember {
        listOf(
            ProcedureSpec(AnalysisProcedure.DESCRIPTIVES, R.string.proc_descriptives, R.string.proc_descriptives_desc, Icons.Default.Analytics),
            ProcedureSpec(AnalysisProcedure.FREQUENCIES, R.string.proc_frequencies, R.string.proc_frequencies_desc, Icons.Default.BarChart),
            ProcedureSpec(AnalysisProcedure.CROSSTABS, R.string.proc_crosstabs, R.string.proc_crosstabs_desc, Icons.Default.GridOn),
            ProcedureSpec(AnalysisProcedure.TTEST, R.string.proc_ttest, R.string.proc_ttest_desc, Icons.Default.CompareArrows),
            ProcedureSpec(AnalysisProcedure.ANOVA, R.string.proc_anova, R.string.proc_anova_desc, Icons.Default.Hub),
            ProcedureSpec(AnalysisProcedure.CORRELATION, R.string.proc_correlation, R.string.proc_correlation_desc, Icons.Default.QueryStats),
            ProcedureSpec(AnalysisProcedure.REGRESSION, R.string.proc_regression, R.string.proc_regression_desc, Icons.Default.StackedLineChart),
            ProcedureSpec(AnalysisProcedure.CHISQUARE, R.string.proc_chisquare, R.string.proc_chisquare_desc, Icons.Default.GridOn),
            ProcedureSpec(AnalysisProcedure.RELIABILITY, R.string.proc_reliability, R.string.proc_reliability_desc, Icons.Default.Verified)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("analyze_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.analyze_title),
            style = MaterialTheme.typography.headlineSmall
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(specs) { spec ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProcedure(spec.procedure) }
                        .testTag("procedure_card_${spec.procedure.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = null,
                                tint = Cyan400
                            )
                            Text(
                                text = stringResource(spec.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = spec.procedure.commandName,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = Cyan400
                        )
                        Text(
                            text = stringResource(spec.descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (selectedProcedure != null) {
        ProcedureConfigDialog(
            procedure = selectedProcedure,
            dataset = dataset,
            onDismiss = { onSelectProcedure(null) },
            onRun = { syntax ->
                onSelectProcedure(null)
                onRunGeneratedSyntax(syntax)
            },
            onOpenInEditor = { syntax ->
                onSelectProcedure(null)
                onSendSyntaxToEditor(syntax)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcedureConfigDialog(
    procedure: AnalysisProcedure,
    dataset: PsppDataset,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
    onOpenInEditor: (String) -> Unit
) {
    val allVarNames = remember(dataset) { dataset.variables.map { it.name } }
    val selectedVars = remember(procedure, dataset) {
        mutableStateListOf<String>().apply {
            when (procedure) {
                AnalysisProcedure.FREQUENCIES, AnalysisProcedure.CHISQUARE -> {
                    addAll(allVarNames.filter { it in listOf("group", "gender") }.ifEmpty { allVarNames.take(2) })
                }
                AnalysisProcedure.CROSSTABS -> {
                    addAll(allVarNames.filter { it == "group" }.ifEmpty { allVarNames.take(1) })
                }
                AnalysisProcedure.RELIABILITY -> {
                    addAll(allVarNames.filter { it.startsWith("qol_") }.ifEmpty { allVarNames.take(4) })
                }
                else -> {
                    addAll(
                        allVarNames.filter { it in listOf("baseline_bp", "followup_bp", "adherence_score", "outcome_score") }
                            .ifEmpty { allVarNames.take(3) }
                    )
                }
            }
        }
    }

    var secondaryVar by remember(procedure) {
        mutableStateOf(
            when (procedure) {
                AnalysisProcedure.CROSSTABS, AnalysisProcedure.TTEST -> "gender"
                AnalysisProcedure.ANOVA -> "group"
                AnalysisProcedure.REGRESSION -> "outcome_score"
                else -> allVarNames.firstOrNull().orEmpty()
            }
        )
    }
    var ttestMode by remember { mutableStateOf(TTestMode.INDEPENDENT) }
    var groupVal1 by remember { mutableStateOf("1") }
    var groupVal2 by remember { mutableStateOf("2") }
    var includeExtraStats by remember { mutableStateOf(true) }
    var editableSyntax by remember { mutableStateOf("") }

    LaunchedEffect(
        procedure,
        selectedVars.toList(),
        secondaryVar,
        ttestMode,
        groupVal1,
        groupVal2,
        includeExtraStats
    ) {
        editableSyntax = PsppSyntaxGenerator.generateProcedureSyntax(
            procedure = procedure,
            selectedVars = selectedVars.toList(),
            secondaryVar = secondaryVar,
            ttestMode = ttestMode,
            groupVal1 = groupVal1,
            groupVal2 = groupVal2,
            includeExtraStats = includeExtraStats
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${procedure.commandName} — Configure & Edit Syntax") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (procedure == AnalysisProcedure.TTEST) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ttestMode == TTestMode.INDEPENDENT,
                            onClick = {
                                ttestMode = TTestMode.INDEPENDENT
                                secondaryVar = "gender"
                            },
                            label = { Text("Independent Samples") }
                        )
                        FilterChip(
                            selected = ttestMode == TTestMode.PAIRED,
                            onClick = {
                                ttestMode = TTestMode.PAIRED
                                secondaryVar = "followup_bp"
                            },
                            label = { Text("Paired Samples") }
                        )
                    }
                }

                Text("1. Select Variables:", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allVarNames.forEach { vName ->
                        val isSelected = vName in selectedVars
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected && selectedVars.size > 1) selectedVars.remove(vName)
                                else if (!isSelected) selectedVars.add(vName)
                            },
                            label = { Text(vName) }
                        )
                    }
                }

                if (procedure in listOf(
                        AnalysisProcedure.CROSSTABS,
                        AnalysisProcedure.TTEST,
                        AnalysisProcedure.ANOVA,
                        AnalysisProcedure.REGRESSION
                    )
                ) {
                    val secTitle = when (procedure) {
                        AnalysisProcedure.CROSSTABS -> "2. Column Variable (BY):"
                        AnalysisProcedure.TTEST -> if (ttestMode == TTestMode.INDEPENDENT) "2. Grouping Variable:" else "2. Paired Variable 2 (WITH):"
                        AnalysisProcedure.ANOVA -> "2. Factor Variable (BY):"
                        AnalysisProcedure.REGRESSION -> "2. Dependent Variable:"
                        else -> "2. Target Variable:"
                    }
                    Text(secTitle, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allVarNames.forEach { vName ->
                            FilterChip(
                                selected = secondaryVar == vName,
                                onClick = { secondaryVar = vName },
                                label = { Text(vName) }
                            )
                        }
                    }
                }

                if (procedure == AnalysisProcedure.TTEST && ttestMode == TTestMode.INDEPENDENT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = groupVal1,
                            onValueChange = { groupVal1 = it },
                            label = { Text("Group 1 Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = groupVal2,
                            onValueChange = { groupVal2 = it },
                            label = { Text("Group 2 Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeExtraStats,
                        onCheckedChange = { includeExtraStats = it }
                    )
                    Text(
                        text = "Include extended dispersion, normality & post-hoc statistics",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = stringResource(R.string.generated_syntax_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = Cyan400
                )

                OutlinedTextField(
                    value = editableSyntax,
                    onValueChange = { editableSyntax = it },
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 12.sp
                    ),
                    minLines = 5,
                    maxLines = 9,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("procedure_generated_syntax_input")
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onOpenInEditor(editableSyntax) },
                    modifier = Modifier.testTag("open_in_syntax_editor_button")
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.copy_to_editor))
                }
                Button(
                    onClick = { onRun(editableSyntax) },
                    modifier = Modifier.testTag("run_procedure_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.run_procedure))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
