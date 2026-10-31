package org.pspp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pspp.mobile.R
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.model.SyntaxDiagnostic
import org.pspp.mobile.ui.theme.Amber400
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.Emerald400
import org.pspp.mobile.ui.theme.Indigo400
import org.pspp.mobile.ui.theme.JetBrainsMonoFontFamily
import org.pspp.mobile.ui.theme.Rose400

private val PSPP_HIGHLIGHT_KEYWORDS = setOf(
    "GET", "DATA", "FILE", "SAVE", "TRANSLATE", "OUTFILE", "TYPE", "DELIMITERS",
    "FIRSTCASE", "VARIABLES", "VARIABLE", "LABELS", "VALUE", "MISSING", "VALUES",
    "LEVEL", "DISPLAY", "DICTIONARY", "DESCRIPTIVES", "FREQUENCIES", "CROSSTABS",
    "TABLES", "BY", "STATISTICS", "CELLS", "FORMAT", "T-TEST", "GROUPS", "PAIRS",
    "WITH", "PAIRED", "ONEWAY", "POSTHOC", "CORRELATIONS", "PRINT", "REGRESSION",
    "DEPENDENT", "METHOD", "ENTER", "NPAR", "TESTS", "CHISQUARE", "EXPECTED",
    "RELIABILITY", "SCALE", "MODEL", "ALPHA", "SUMMARY", "BEGIN", "END",
    "COMPUTE", "IF", "SELECT", "EXECUTE"
)

class PsppSyntaxHighlighter(private val isDark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            val cmdColor = if (isDark) Cyan400 else Color(0xFF0369A1)
            val subCmdColor = if (isDark) Indigo400 else Color(0xFF4338CA)
            val strColor = if (isDark) Emerald400 else Color(0xFF047857)
            val numColor = if (isDark) Amber400 else Color(0xFFB45309)
            val commentColor = if (isDark) Color(0xFF64748B) else Color(0xFF64748B)

            // Keywords
            Regex("\\b([A-Za-z][A-Za-z0-9_-]*)\\b").findAll(raw).forEach { m ->
                if (m.value.uppercase() in PSPP_HIGHLIGHT_KEYWORDS) {
                    addStyle(
                        SpanStyle(color = cmdColor, fontWeight = FontWeight.Bold),
                        m.range.first,
                        m.range.last + 1
                    )
                }
            }
            // Subcommands starting with /
            Regex("/[A-Za-z0-9_]+").findAll(raw).forEach { m ->
                addStyle(
                    SpanStyle(color = subCmdColor, fontWeight = FontWeight.SemiBold),
                    m.range.first,
                    m.range.last + 1
                )
            }
            // Numbers
            Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = numColor), m.range.first, m.range.last + 1)
            }
            // Single-quoted strings
            Regex("'[^'\n]*'").findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = strColor), m.range.first, m.range.last + 1)
            }
            // Comment lines starting with *
            var lineStart = 0
            for (line in raw.split('\n')) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.uppercase().startsWith("COMMENT")) {
                    addStyle(
                        SpanStyle(color = commentColor),
                        lineStart,
                        (lineStart + line.length).coerceAtMost(raw.length)
                    )
                }
                lineStart += line.length + 1
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyntaxEditorScreen(
    isNativeJniLoaded: Boolean = false,
    syntaxText: String,
    selectionStartLine: Int,
    selectionEndLine: Int,
    diagnostics: List<SyntaxDiagnostic>,
    isRunning: Boolean,
    elapsedRunMs: Long,
    onSyntaxChange: (String) -> Unit,
    onSelectionRangeChange: (Int, Int) -> Unit,
    onRunAll: () -> Unit,
    onRunSelection: () -> Unit,
    onCancelRun: () -> Unit,
    onLoadSample: (String) -> Unit,
    onOpenSpsSaf: () -> Unit,
    onSaveSpsSaf: () -> Unit
) {
    var samplesExpanded by remember { mutableStateOf(false) }
    val lines = remember(syntaxText) { syntaxText.lines() }
    val errorLineMap = remember(diagnostics) { diagnostics.associateBy { it.line } }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("syntax_editor_screen"),
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

        // Action Toolbar Card
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRunAll,
                        enabled = !isRunning,
                        modifier = Modifier.testTag("run_all_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.run_all))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.run_all))
                    }

                    FilledTonalButton(
                        onClick = onRunSelection,
                        enabled = !isRunning,
                        modifier = Modifier.testTag("run_selection_button")
                    ) {
                        Icon(Icons.Default.PlaylistPlay, contentDescription = stringResource(R.string.run_selection))
                        Spacer(Modifier.width(6.dp))
                        Text("${stringResource(R.string.run_selection)} (L$selectionStartLine–L$selectionEndLine)")
                    }

                    if (isRunning) {
                        Button(
                            onClick = onCancelRun,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("cancel_run_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cancel_run))
                            Spacer(Modifier.width(4.dp))
                            Text("${stringResource(R.string.cancel_run)} (${elapsedRunMs}ms)")
                        }
                    }

                    Box {
                        OutlinedButton(
                            onClick = { samplesExpanded = true },
                            modifier = Modifier.testTag("load_sample_button")
                        ) {
                            Icon(Icons.Default.Code, contentDescription = stringResource(R.string.load_sample))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.load_sample))
                        }
                        DropdownMenu(
                            expanded = samplesExpanded,
                            onDismissRequest = { samplesExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("1. descriptives.sps (Descriptives & Frequencies)") },
                                onClick = {
                                    samplesExpanded = false
                                    onLoadSample("descriptives.sps")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("2. crosstabs.sps (Crosstabs, T-Test & ANOVA)") },
                                onClick = {
                                    samplesExpanded = false
                                    onLoadSample("crosstabs.sps")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("3. regression.sps (Correlation, OLS Regression & Alpha)") },
                                onClick = {
                                    samplesExpanded = false
                                    onLoadSample("regression.sps")
                                }
                            )
                        }
                    }

                    AssistChip(
                        onClick = onOpenSpsSaf,
                        label = { Text(stringResource(R.string.open_sps)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.open_sps),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("open_sps_button")
                    )

                    AssistChip(
                        onClick = onSaveSpsSaf,
                        label = { Text(stringResource(R.string.save_sps)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(R.string.save_sps),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("save_sps_button")
                    )
                }

                // Line Selection Range Quick Controls + Syntax Status Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Selection:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AssistChip(
                            onClick = {
                                onSelectionRangeChange((selectionStartLine - 5).coerceAtLeast(1), selectionEndLine)
                            },
                            label = { Text("Start -5") }
                        )
                        AssistChip(
                            onClick = {
                                onSelectionRangeChange(selectionStartLine + 5, selectionEndLine + 5)
                            },
                            label = { Text("Start +5") }
                        )
                        AssistChip(
                            onClick = {
                                onSelectionRangeChange(1, lines.size.coerceAtLeast(1))
                            },
                            label = { Text("All (${lines.size}L)") }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (diagnostics.isEmpty()) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Emerald400,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.syntax_no_errors),
                                style = MaterialTheme.typography.labelMedium,
                                color = Emerald400
                            )
                        } else {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = Rose400,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${diagnostics.size} ${stringResource(R.string.syntax_errors_found)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Rose400
                            )
                        }
                    }
                }

                if (isRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // Diagnostics Banner if any syntax error or warning is flagged
        if (diagnostics.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("syntax_diagnostics_banner")
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    diagnostics.take(3).forEach { diag ->
                        Text(
                            text = "[${diag.severity}] ${diag.message}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Code Editor Surface with Line Number & Error Marker Gutter
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .padding(vertical = 10.dp)
            ) {
                // Gutter with line numbers & error dots
                Column(
                    modifier = Modifier
                        .width(54.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    lines.forEachIndexed { idx, _ ->
                        val lineNum = idx + 1
                        val hasErr = errorLineMap.containsKey(lineNum)
                        val inSelection = lineNum in selectionStartLine..selectionEndLine
                        Row(
                            modifier = Modifier
                                .height(21.dp)
                                .fillMaxWidth()
                                .background(
                                    if (hasErr) Rose400.copy(alpha = 0.22f)
                                    else if (inSelection) Cyan400.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (hasErr) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Rose400, CircleShape)
                                )
                            } else {
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = lineNum.toString(),
                                style = TextStyle(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 21.sp,
                                    color = if (hasErr) Rose400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                // Editable Syntax Field
                val highlighter = remember { PsppSyntaxHighlighter(isDark = true) }
                BasicTextField(
                    value = syntaxText,
                    onValueChange = onSyntaxChange,
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = highlighter,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(horizontal = 10.dp)
                        .testTag("syntax_code_input")
                )
            }
        }
    }
}
