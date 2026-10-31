package org.pspp.mobile.model

import androidx.compose.ui.unit.LayoutDirection
import java.text.NumberFormat
import java.util.Locale

enum class VariableType(val psppCode: String, val displayLabel: String) {
    NUMERIC("F", "Numeric"),
    COMMA("COMMA", "Comma"),
    DOT("DOT", "Dot"),
    DOLLAR("DOLLAR", "Dollar"),
    DATE("DATE", "Date"),
    STRING("A", "String")
}

enum class MeasureLevel(val psppKeyword: String, val displayLabel: String) {
    SCALE("SCALE", "Scale"),
    ORDINAL("ORDINAL", "Ordinal"),
    NOMINAL("NOMINAL", "Nominal")
}

data class PsppVariable(
    val name: String,
    val type: VariableType = VariableType.NUMERIC,
    val width: Int = 8,
    val decimals: Int = 2,
    val label: String = "",
    val valueLabels: Map<String, String> = emptyMap(),
    val missingValues: List<String> = emptyList(),
    val measure: MeasureLevel = MeasureLevel.SCALE
) {
    fun formatSpec(): String {
        return if (type == VariableType.STRING) {
            "A$width"
        } else if (decimals > 0) {
            "${type.psppCode}$width.$decimals"
        } else {
            "${type.psppCode}$width.0"
        }
    }
}

data class PsppDataset(
    val name: String,
    val sourcePath: String = "",
    val variables: List<PsppVariable>,
    val rows: List<List<String>>,
    val modified: Boolean = false
) {
    val caseCount: Int get() = rows.size
    val variableCount: Int get() = variables.size
}

data class SyntaxDiagnostic(
    val line: Int,
    val severity: String, // "ERROR" or "WARNING"
    val message: String
)

data class PsppRunResult(
    val exitCode: Int,
    val cancelled: Boolean,
    val htmlContent: String,
    val plainTextContent: String,
    val csvContent: String,
    val diagnostics: List<SyntaxDiagnostic>,
    val durationMs: Long,
    val usedNativeJni: Boolean,
    val outputDir: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class RecentFileItem(
    val name: String,
    val uriOrPath: String,
    val format: String,
    val timestampMs: Long,
    val sizeBytes: Long
)

data class CsvColumnPreview(
    val index: Int,
    val name: String,
    val inferredType: VariableType,
    val inferredMeasure: MeasureLevel,
    val sampleValues: List<String>
)

data class CsvImportPreviewState(
    val fileName: String,
    val rawContent: String,
    val delimiter: Char = ',',
    val hasHeader: Boolean = true,
    val columns: List<CsvColumnPreview> = emptyList(),
    val totalRows: Int = 0
)

enum class AnalysisProcedure(
    val id: String,
    val commandName: String
) {
    DESCRIPTIVES("descriptives", "DESCRIPTIVES"),
    FREQUENCIES("frequencies", "FREQUENCIES"),
    CROSSTABS("crosstabs", "CROSSTABS"),
    TTEST("ttest", "T-TEST"),
    ANOVA("anova", "ONEWAY"),
    CORRELATION("correlation", "CORRELATIONS"),
    REGRESSION("regression", "REGRESSION"),
    CHISQUARE("chisquare", "NPAR TESTS /CHISQUARE"),
    RELIABILITY("reliability", "RELIABILITY")
}

enum class TTestMode {
    INDEPENDENT,
    PAIRED
}

enum class AppScreen {
    SYNTAX,
    DATA,
    ANALYZE,
    OUTPUT,
    FILES,
    ABOUT
}

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val locale: Locale,
    val layoutDirection: LayoutDirection
) {
    EN("en", "English", Locale.ENGLISH, LayoutDirection.Ltr),
    AR("ar", "العربية (RTL)", Locale("ar"), LayoutDirection.Rtl),
    FR("fr", "Français", Locale.FRENCH, LayoutDirection.Ltr);

    fun formatNumber(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = false
        }
        return nf.format(value)
    }

    fun formatInt(value: Int): String {
        val nf = NumberFormat.getIntegerInstance(locale).apply {
            isGroupingUsed = false
        }
        return nf.format(value)
    }
}
