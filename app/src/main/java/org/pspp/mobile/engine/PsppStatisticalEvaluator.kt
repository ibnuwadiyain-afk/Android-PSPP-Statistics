package org.pspp.mobile.engine

import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.AppLanguage
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.PsppRunResult
import org.pspp.mobile.model.PsppVariable
import org.pspp.mobile.model.SyntaxDiagnostic
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Executes PSPP syntax commands against the active dataset and produces
 * HTML, Plain Text, and CSV output plus line-accurate syntax diagnostics
 * when running in fallback/approximation mode (when libpspp_jni.so is not loaded
 * and allowFallback=true). Every HTML, Plain Text, and CSV output is stamped with
 * [APPROXIMATION_BANNER].
 */
object PsppStatisticalEvaluator {

    const val APPROXIMATION_BANNER = "Approximation mode: results are NOT from the real PSPP engine"

    private val KNOWN_COMMANDS = setOf(
        "GET", "SAVE", "IMPORT", "EXPORT", "DATA", "BEGIN", "END",
        "VARIABLE", "VALUE", "MISSING", "FORMATS", "DISPLAY",
        "DESCRIPTIVES", "FREQUENCIES", "CROSSTABS", "T-TEST", "ONEWAY",
        "CORRELATIONS", "REGRESSION", "NPAR", "RELIABILITY",
        "COMPUTE", "IF", "SELECT", "FILTER", "WEIGHT", "SORT",
        "SPLIT", "TEMPORARY", "TITLE", "SUBTITLE", " LIST", "LIST", "EXECUTE", "COMMENT"
    )

    fun validateSyntax(script: String, dataset: PsppDataset): List<SyntaxDiagnostic> {
        val diagnostics = mutableListOf<SyntaxDiagnostic>()
        val rawLines = script.replace("\r\n", "\n").split('\n')
        var inInlineData = false
        var currentCmdStartLine = -1
        val cmdBuffer = StringBuilder()

        for (i in rawLines.indices) {
            val lineNum = i + 1
            val trimmed = rawLines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.uppercase().startsWith("COMMENT")) {
                continue
            }
            if (trimmed.uppercase().startsWith("BEGIN DATA")) {
                inInlineData = true
                continue
            }
            if (trimmed.uppercase().startsWith("END DATA")) {
                inInlineData = false
                continue
            }
            if (inInlineData) continue

            // Check balanced single quotes on the line
            val singleQuotes = trimmed.count { it == '\'' }
            if (singleQuotes % 2 != 0) {
                diagnostics.add(
                    SyntaxDiagnostic(
                        line = lineNum,
                        severity = "ERROR",
                        message = "Line $lineNum: Unterminated string literal (unbalanced single quote)."
                    )
                )
            }

            if (currentCmdStartLine == -1) {
                currentCmdStartLine = lineNum
                val firstToken = trimmed.split(Regex("[\\s/=.]+")).firstOrNull()?.uppercase().orEmpty()
                if (firstToken.isNotEmpty() && firstToken !in KNOWN_COMMANDS) {
                    diagnostics.add(
                        SyntaxDiagnostic(
                            line = lineNum,
                            severity = "ERROR",
                            message = "Line $lineNum: Unknown PSPP command '$firstToken'."
                        )
                    )
                }
            }
            cmdBuffer.append(" ").append(trimmed)
            if (trimmed.endsWith(".")) {
                currentCmdStartLine = -1
                cmdBuffer.clear()
            }
        }

        if (currentCmdStartLine != -1 && cmdBuffer.isNotBlank()) {
            diagnostics.add(
                SyntaxDiagnostic(
                    line = currentCmdStartLine,
                    severity = "WARNING",
                    message = "Line $currentCmdStartLine: Command does not end with a period ('.')."
                )
            )
        }
        return diagnostics
    }

    fun evaluateScript(
        script: String,
        initialDataset: PsppDataset,
        samplesDir: File?,
        outputDir: File,
        language: AppLanguage,
        isCancelled: () -> Boolean
    ): Pair<PsppRunResult, PsppDataset> {
        val startMs = System.currentTimeMillis()
        outputDir.mkdirs()

        var activeDataset = initialDataset
        val diagnostics = validateSyntax(script, activeDataset).toMutableList()

        val htmlSections = mutableListOf<String>()
        val txtSections = mutableListOf<String>()
        val csvRows = mutableListOf<List<String>>()

        val statements = splitStatements(script)

        for ((stmtLine, stmtRaw) in statements) {
            if (isCancelled()) {
                return Pair(
                    PsppRunResult(
                        exitCode = 130,
                        cancelled = true,
                        htmlContent = wrapHtml(htmlSections, language, "Cancelled"),
                        plainTextContent = "$APPROXIMATION_BANNER\n\n" + txtSections.joinToString("\n\n"),
                        csvContent = formatCsv(csvRows),
                        diagnostics = diagnostics,
                        durationMs = System.currentTimeMillis() - startMs,
                        usedNativeJni = false,
                        outputDir = outputDir.absolutePath
                    ),
                    activeDataset
                )
            }

            val stmt = stmtRaw.trim().removeSuffix(".").trim()
            if (stmt.isEmpty() || stmt.startsWith("*")) continue
            val upper = stmt.uppercase()

            when {
                upper.startsWith("GET DATA") || upper.startsWith("GET FILE") -> {
                    val fileMatch = Regex("FILE\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE).find(stmt)
                    val fileName = fileMatch?.groupValues?.get(1).orEmpty()
                    if (fileName.isNotEmpty()) {
                        val directFile = File(fileName)
                        val candidate = when {
                            directFile.exists() -> directFile
                            samplesDir != null -> File(samplesDir, directFile.name)
                            else -> null
                        }
                        if (candidate != null && candidate.exists()) {
                            activeDataset = if (candidate.name.endsWith(".sav", true)) {
                                SavBinaryCodec.readSavBytes(candidate.readBytes(), candidate.nameWithoutExtension)
                            } else {
                                SavBinaryCodec.parseCsvToDataset(candidate.nameWithoutExtension, candidate.readText())
                            }
                        }
                    }
                }

                upper.startsWith("VARIABLE LABELS") -> {
                    val body = stmt.substring("VARIABLE LABELS".length).trim()
                    val pairRegex = Regex("([a-zA-Z0-9_]+)\\s+'([^']*)'")
                    val labelUpdates = pairRegex.findAll(body).associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    if (labelUpdates.isNotEmpty()) {
                        activeDataset = activeDataset.copy(
                            variables = activeDataset.variables.map { v ->
                                labelUpdates[v.name.lowercase()]?.let { v.copy(label = it) } ?: v
                            }
                        )
                    }
                }

                upper.startsWith("VALUE LABELS") -> {
                    val body = stmt.substring("VALUE LABELS".length).trim()
                    val clauses = body.split("/")
                    var updatedVars = activeDataset.variables
                    for (clause in clauses) {
                        val trimmedClause = clause.trim()
                        if (trimmedClause.isEmpty()) continue
                        val varName = trimmedClause.substringBefore(" ").trim().lowercase()
                        val rest = trimmedClause.substringAfter(" ", "")
                        val valPairs = Regex("([0-9.-]+|'[^']+')\\s+'([^']*)'").findAll(rest)
                            .associate { it.groupValues[1].trim('\'') to it.groupValues[2] }
                        if (valPairs.isNotEmpty()) {
                            updatedVars = updatedVars.map { v ->
                                if (v.name.lowercase() == varName) v.copy(valueLabels = valPairs) else v
                            }
                        }
                    }
                    activeDataset = activeDataset.copy(variables = updatedVars)
                }

                upper.startsWith("DISPLAY DICTIONARY") -> {
                    renderDictionary(activeDataset, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("DESCRIPTIVES") -> {
                    val vars = extractVariablesList(stmt, "VARIABLES", activeDataset)
                    renderDescriptives(activeDataset, vars, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("FREQUENCIES") -> {
                    val vars = extractVariablesList(stmt, "VARIABLES", activeDataset)
                    renderFrequencies(activeDataset, vars, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("CROSSTABS") -> {
                    val tablesMatch = Regex("TABLES\\s*=\\s*([a-zA-Z0-9_]+)\\s+BY\\s+([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(stmt)
                    val rowVar = tablesMatch?.groupValues?.get(1) ?: activeDataset.variables.firstOrNull()?.name.orEmpty()
                    val colVar = tablesMatch?.groupValues?.get(2) ?: activeDataset.variables.getOrNull(1)?.name.orEmpty()
                    renderCrosstabs(activeDataset, rowVar, colVar, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("T-TEST") -> {
                    if (upper.contains("PAIRS")) {
                        val pairMatch = Regex("PAIRS\\s*=\\s*([a-zA-Z0-9_]+)\\s+WITH\\s+([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(stmt)
                        val v1 = pairMatch?.groupValues?.get(1) ?: "baseline_bp"
                        val v2 = pairMatch?.groupValues?.get(2) ?: "followup_bp"
                        renderPairedTTest(activeDataset, v1, v2, language, htmlSections, txtSections, csvRows)
                    } else {
                        val grpMatch = Regex("GROUPS\\s*=\\s*([a-zA-Z0-9_]+)\\s*\\(\\s*([^,\\s)]+)\\s*[,\\s]\\s*([^)\\s]+)\\s*\\)", RegexOption.IGNORE_CASE).find(stmt)
                        val grpVar = grpMatch?.groupValues?.get(1) ?: "gender"
                        val g1 = grpMatch?.groupValues?.get(2) ?: "1"
                        val g2 = grpMatch?.groupValues?.get(3) ?: "2"
                        val vars = extractVariablesList(stmt, "VARIABLES", activeDataset).filter { !it.equals(grpVar, true) }
                        renderIndependentTTest(activeDataset, grpVar, g1, g2, vars, language, htmlSections, txtSections, csvRows)
                    }
                }

                upper.startsWith("ONEWAY") -> {
                    val onewayMatch = Regex("ONEWAY\\s+(?:VARIABLES\\s*=\\s*)?(.+?)\\s+BY\\s+([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(stmt)
                    val depTokens = onewayMatch?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
                    val factorVar = onewayMatch?.groupValues?.get(2) ?: "group"
                    renderOneWayAnova(activeDataset, depTokens, factorVar, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("CORRELATIONS") -> {
                    val vars = extractVariablesList(stmt, "VARIABLES", activeDataset)
                    renderCorrelations(activeDataset, vars, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("REGRESSION") -> {
                    val depMatch = Regex("DEPENDENT\\s*=\\s*([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(stmt)
                    val depVar = depMatch?.groupValues?.get(1) ?: "outcome_score"
                    val enterMatch = Regex("METHOD\\s*=\\s*ENTER\\s+([^/]+)", RegexOption.IGNORE_CASE).find(stmt)
                    val indepVars = enterMatch?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))
                        ?.filter { it.isNotBlank() && !it.equals(depVar, true) }
                        ?: extractVariablesList(stmt, "VARIABLES", activeDataset).filter { !it.equals(depVar, true) }
                    renderLinearRegression(activeDataset, depVar, indepVars, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("NPAR TESTS") -> {
                    val chiMatch = Regex("CHISQUARE\\s*=\\s*([^/]+)", RegexOption.IGNORE_CASE).find(stmt)
                    val vars = chiMatch?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
                    renderChiSquareGoodness(activeDataset, vars, language, htmlSections, txtSections, csvRows)
                }

                upper.startsWith("RELIABILITY") -> {
                    val vars = extractVariablesList(stmt, "VARIABLES", activeDataset)
                    renderReliability(activeDataset, vars, language, htmlSections, txtSections, csvRows)
                }
            }
        }

        if (htmlSections.isEmpty()) {
            renderDescriptives(
                activeDataset,
                activeDataset.variables.map { it.name }.take(6),
                language,
                htmlSections,
                txtSections,
                csvRows
            )
        }

        val finalHtml = wrapHtml(htmlSections, language, activeDataset.name)
        val finalTxt = buildString {
            appendLine(APPROXIMATION_BANNER)
            appendLine()
            append(txtSections.joinToString("\n\n"))
        }
        val finalCsv = formatCsv(csvRows)

        File(outputDir, "output.html").writeText(finalHtml)
        File(outputDir, "output.txt").writeText(finalTxt)
        File(outputDir, "output.csv").writeText(finalCsv)

        val hasErrors = diagnostics.any { it.severity == "ERROR" }
        return Pair(
            PsppRunResult(
                exitCode = if (hasErrors) 1 else 0,
                cancelled = false,
                htmlContent = finalHtml,
                plainTextContent = finalTxt,
                csvContent = finalCsv,
                diagnostics = diagnostics,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(12L),
                usedNativeJni = false,
                outputDir = outputDir.absolutePath
            ),
            activeDataset
        )
    }

    private fun renderDictionary(
        ds: PsppDataset,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val title = when (lang) {
            AppLanguage.AR -> "قاموس المتغيرات (${ds.name})"
            AppLanguage.FR -> "Dictionnaire des Variables (${ds.name})"
            AppLanguage.EN -> "Variable Dictionary (${ds.name})"
        }
        val headers = listOf("Variable", "Label", "Format", "Measure", "Value Labels")
        val rows = ds.variables.map { v ->
            val vLabels = v.valueLabels.entries.joinToString("; ") { "${it.key}='${it.value}'" }.ifEmpty { "—" }
            listOf(v.name, v.label.ifEmpty { "—" }, v.formatSpec(), v.measure.displayLabel, vLabels)
        }
        appendTable(title, "DISPLAY DICTIONARY", headers, rows, html, txt, csv)
    }

    private fun renderDescriptives(
        ds: PsppDataset,
        varNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val title = when (lang) {
            AppLanguage.AR -> "الإحصاءات الوصفية (Descriptive Statistics)"
            AppLanguage.FR -> "Statistiques Descriptives (Descriptive Statistics)"
            AppLanguage.EN -> "Descriptive Statistics"
        }
        val headers = listOf("Variable", "Label", "N", "Mean", "Std Dev", "Variance", "SE Mean", "Skewness", "Kurtosis", "Minimum", "Maximum")
        val rows = mutableListOf<List<String>>()

        for (vName in varNames) {
            val v = ds.variables.find { it.name.equals(vName, true) } ?: continue
            val values = numericColumn(ds, v.name)
            if (values.isEmpty()) continue
            val n = values.size
            val mean = values.average()
            val variance = if (n > 1) values.sumOf { (it - mean).pow(2) } / (n - 1) else 0.0
            val sd = sqrt(variance)
            val se = if (n > 0) sd / sqrt(n.toDouble()) else 0.0
            val min = values.minOrNull() ?: 0.0
            val max = values.maxOrNull() ?: 0.0
            val skew = if (n > 2 && sd > 0) {
                (values.sumOf { ((it - mean) / sd).pow(3) } * n) / ((n - 1.0) * (n - 2.0))
            } else 0.0
            val kurt = if (n > 3 && sd > 0) {
                (values.sumOf { ((it - mean) / sd).pow(4) } / n) - 3.0
            } else 0.0

            rows.add(
                listOf(
                    v.name,
                    v.label.ifEmpty { v.name },
                    lang.formatInt(n),
                    lang.formatNumber(mean, 2),
                    lang.formatNumber(sd, 2),
                    lang.formatNumber(variance, 2),
                    lang.formatNumber(se, 3),
                    lang.formatNumber(skew, 3),
                    lang.formatNumber(kurt, 3),
                    lang.formatNumber(min, 2),
                    lang.formatNumber(max, 2)
                )
            )
        }
        appendTable(title, "DESCRIPTIVES", headers, rows, html, txt, csv)
    }

    private fun renderFrequencies(
        ds: PsppDataset,
        varNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        for (vName in varNames) {
            val v = ds.variables.find { it.name.equals(vName, true) } ?: continue
            val colIdx = ds.variables.indexOf(v)
            val rawVals = ds.rows.mapNotNull { r -> r.getOrNull(colIdx)?.trim()?.takeIf { it.isNotEmpty() } }
            val total = rawVals.size.coerceAtLeast(1)
            val counts = rawVals.groupingBy { it }.eachCount().toSortedMap()
            var cumPct = 0.0
            val tableRows = counts.map { (valKey, cnt) ->
                val pct = (cnt * 100.0) / total
                cumPct += pct
                val valLabel = v.valueLabels[valKey] ?: v.valueLabels[valKey.removeSuffix(".0")] ?: valKey
                listOf(
                    valKey,
                    valLabel,
                    lang.formatInt(cnt),
                    "${lang.formatNumber(pct, 1)}%",
                    "${lang.formatNumber(cumPct, 1)}%"
                )
            } + listOf(
                listOf("Total", v.label.ifEmpty { v.name }, lang.formatInt(rawVals.size), "100.0%", "100.0%")
            )
            val title = when (lang) {
                AppLanguage.AR -> "جدول التكرارات: ${v.name} (${v.label})"
                AppLanguage.FR -> "Tableau des Fréquences: ${v.name} (${v.label})"
                AppLanguage.EN -> "Frequency Table: ${v.name} (${v.label})"
            }
            appendTable(
                title,
                "FREQUENCIES VARIABLES=${v.name}",
                listOf("Value", "Value Label", "Frequency", "Valid Percent", "Cumulative %"),
                tableRows,
                html,
                txt,
                csv
            )
        }
    }

    private fun renderCrosstabs(
        ds: PsppDataset,
        rowVarName: String,
        colVarName: String,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val rVar = ds.variables.find { it.name.equals(rowVarName, true) } ?: ds.variables.firstOrNull() ?: return
        val cVar = ds.variables.find { it.name.equals(colVarName, true) } ?: ds.variables.getOrNull(1) ?: return
        val rIdx = ds.variables.indexOf(rVar)
        val cIdx = ds.variables.indexOf(cVar)

        val pairs = ds.rows.mapNotNull { row ->
            val rv = row.getOrNull(rIdx)?.trim().orEmpty()
            val cv = row.getOrNull(cIdx)?.trim().orEmpty()
            if (rv.isNotEmpty() && cv.isNotEmpty()) rv to cv else null
        }
        val rowKeys = pairs.map { it.first }.distinct().sorted()
        val colKeys = pairs.map { it.second }.distinct().sorted()
        val nTotal = pairs.size.coerceAtLeast(1)

        val headers = listOf("${rVar.name} \\ ${cVar.name}") + colKeys.map { k ->
            val lbl = cVar.valueLabels[k] ?: k
            "$k ($lbl)"
        } + listOf("Row Total")

        val tableRows = mutableListOf<List<String>>()
        for (rk in rowKeys) {
            val rLabel = rVar.valueLabels[rk] ?: rk
            val rTotal = pairs.count { it.first == rk }
            val cells = colKeys.map { ck ->
                val cnt = pairs.count { it.first == rk && it.second == ck }
                val rowPct = if (rTotal > 0) (cnt * 100.0) / rTotal else 0.0
                "${lang.formatInt(cnt)} (${lang.formatNumber(rowPct, 1)}%)"
            }
            tableRows.add(listOf("$rk ($rLabel)") + cells + listOf(lang.formatInt(rTotal)))
        }

        // Pearson Chi-Square
        var chiSq = 0.0
        for (rk in rowKeys) {
            val rCount = pairs.count { it.first == rk }.toDouble()
            for (ck in colKeys) {
                val cCount = pairs.count { it.second == ck }.toDouble()
                val obs = pairs.count { it.first == rk && it.second == ck }.toDouble()
                val expVal = (rCount * cCount) / nTotal
                if (expVal > 0) {
                    chiSq += (obs - expVal).pow(2) / expVal
                }
            }
        }
        val df = ((rowKeys.size - 1) * (colKeys.size - 1)).coerceAtLeast(1)
        val pVal = chiSquarePValue(chiSq, df)
        val phi = sqrt(chiSq / nTotal)

        appendTable(
            title = "Crosstabulation: ${rVar.name} × ${cVar.name}",
            subtitle = "CROSSTABS /TABLES=${rVar.name} BY ${cVar.name}",
            headers = headers,
            rows = tableRows,
            html = html,
            txt = txt,
            csv = csv
        )

        appendTable(
            title = "Chi-Square Tests (${rVar.name} × ${cVar.name})",
            subtitle = "Pearson Chi-Square & Symmetric Measures",
            headers = listOf("Statistic", "Value", "df", "Asymp. Sig. (2-sided)"),
            rows = listOf(
                listOf("Pearson Chi-Square", lang.formatNumber(chiSq, 3), lang.formatInt(df), lang.formatNumber(pVal, 4)),
                listOf("Phi / Cramer's V", lang.formatNumber(phi, 3), "—", lang.formatNumber(pVal, 4)),
                listOf("N of Valid Cases", lang.formatInt(pairs.size), "—", "—")
            ),
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderIndependentTTest(
        ds: PsppDataset,
        grpVarName: String,
        g1: String,
        g2: String,
        testVars: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val gVar = ds.variables.find { it.name.equals(grpVarName, true) } ?: return
        val gIdx = ds.variables.indexOf(gVar)
        val rows = mutableListOf<List<String>>()

        for (vName in testVars) {
            val v = ds.variables.find { it.name.equals(vName, true) } ?: continue
            val vIdx = ds.variables.indexOf(v)
            val vals1 = ds.rows.mapNotNull { r ->
                val g = r.getOrNull(gIdx)?.trim().orEmpty()
                if (g == g1 || g == "$g1.0") r.getOrNull(vIdx)?.toDoubleOrNull() else null
            }
            val vals2 = ds.rows.mapNotNull { r ->
                val g = r.getOrNull(gIdx)?.trim().orEmpty()
                if (g == g2 || g == "$g2.0") r.getOrNull(vIdx)?.toDoubleOrNull() else null
            }
            if (vals1.size < 2 || vals2.size < 2) continue
            val m1 = vals1.average()
            val m2 = vals2.average()
            val var1 = vals1.sumOf { (it - m1).pow(2) } / (vals1.size - 1)
            val var2 = vals2.sumOf { (it - m2).pow(2) } / (vals2.size - 1)
            val df = vals1.size + vals2.size - 2
            val pooledVar = (((vals1.size - 1) * var1) + ((vals2.size - 1) * var2)) / df
            val seDiff = sqrt(pooledVar * (1.0 / vals1.size + 1.0 / vals2.size))
            val diff = m1 - m2
            val tStat = if (seDiff > 0) diff / seDiff else 0.0
            val pVal = twoTailedTPValue(abs(tStat), df)
            val ciLow = diff - 1.96 * seDiff
            val ciHigh = diff + 1.96 * seDiff

            rows.add(
                listOf(
                    v.name,
                    "${lang.formatNumber(m1, 2)} (n=${vals1.size})",
                    "${lang.formatNumber(m2, 2)} (n=${vals2.size})",
                    lang.formatNumber(tStat, 3),
                    lang.formatInt(df),
                    lang.formatNumber(pVal, 4),
                    lang.formatNumber(diff, 2),
                    "[${lang.formatNumber(ciLow, 2)}, ${lang.formatNumber(ciHigh, 2)}]"
                )
            )
        }

        appendTable(
            title = "Independent Samples T-Test (Groups: $grpVarName = $g1 vs $g2)",
            subtitle = "T-TEST GROUPS=$grpVarName($g1, $g2)",
            headers = listOf("Variable", "Group $g1 Mean", "Group $g2 Mean", "t", "df", "Sig. (2-tailed)", "Mean Diff", "95% CI of Diff"),
            rows = rows,
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderPairedTTest(
        ds: PsppDataset,
        var1Name: String,
        var2Name: String,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val idx1 = ds.variables.indexOfFirst { it.name.equals(var1Name, true) }
        val idx2 = ds.variables.indexOfFirst { it.name.equals(var2Name, true) }
        if (idx1 < 0 || idx2 < 0) return

        val diffs = ds.rows.mapNotNull { r ->
            val a = r.getOrNull(idx1)?.toDoubleOrNull()
            val b = r.getOrNull(idx2)?.toDoubleOrNull()
            if (a != null && b != null) a - b else null
        }
        if (diffs.size < 2) return
        val n = diffs.size
        val meanDiff = diffs.average()
        val sdDiff = sqrt(diffs.sumOf { (it - meanDiff).pow(2) } / (n - 1))
        val seDiff = sdDiff / sqrt(n.toDouble())
        val tStat = if (seDiff > 0) meanDiff / seDiff else 0.0
        val df = n - 1
        val pVal = twoTailedTPValue(abs(tStat), df)

        appendTable(
            title = "Paired Samples T-Test ($var1Name - $var2Name)",
            subtitle = "T-TEST PAIRS=$var1Name WITH $var2Name (PAIRED)",
            headers = listOf("Pair", "N", "Mean Diff", "Std. Dev.", "Std. Error", "t", "df", "Sig. (2-tailed)"),
            rows = listOf(
                listOf(
                    "$var1Name - $var2Name",
                    lang.formatInt(n),
                    lang.formatNumber(meanDiff, 3),
                    lang.formatNumber(sdDiff, 3),
                    lang.formatNumber(seDiff, 3),
                    lang.formatNumber(tStat, 3),
                    lang.formatInt(df),
                    lang.formatNumber(pVal, 4)
                )
            ),
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderOneWayAnova(
        ds: PsppDataset,
        depVars: List<String>,
        factorName: String,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val fIdx = ds.variables.indexOfFirst { it.name.equals(factorName, true) }
        if (fIdx < 0) return
        val rows = mutableListOf<List<String>>()

        for (dep in depVars) {
            val dIdx = ds.variables.indexOfFirst { it.name.equals(dep, true) }
            if (dIdx < 0) continue
            val grouped = ds.rows.mapNotNull { r ->
                val g = r.getOrNull(fIdx)?.trim().orEmpty()
                val v = r.getOrNull(dIdx)?.toDoubleOrNull()
                if (g.isNotEmpty() && v != null) g to v else null
            }.groupBy({ it.first }, { it.second })

            if (grouped.size < 2) continue
            val allVals = grouped.values.flatten()
            val grandMean = allVals.average()
            val ssBetween = grouped.values.sumOf { gVals ->
                gVals.size * (gVals.average() - grandMean).pow(2)
            }
            val ssWithin = grouped.values.sumOf { gVals ->
                val gm = gVals.average()
                gVals.sumOf { (it - gm).pow(2) }
            }
            val dfBetween = (grouped.size - 1).coerceAtLeast(1)
            val dfWithin = (allVals.size - grouped.size).coerceAtLeast(1)
            val msBetween = ssBetween / dfBetween
            val msWithin = ssWithin / dfWithin
            val fRatio = if (msWithin > 0) msBetween / msWithin else 0.0
            val pVal = fTestPValue(fRatio, dfBetween, dfWithin)

            rows.add(
                listOf(
                    dep,
                    "Between Groups",
                    lang.formatNumber(ssBetween, 2),
                    lang.formatInt(dfBetween),
                    lang.formatNumber(msBetween, 2),
                    lang.formatNumber(fRatio, 3),
                    lang.formatNumber(pVal, 4)
                )
            )
            rows.add(
                listOf(
                    dep,
                    "Within Groups",
                    lang.formatNumber(ssWithin, 2),
                    lang.formatInt(dfWithin),
                    lang.formatNumber(msWithin, 2),
                    "—",
                    "—"
                )
            )
            rows.add(
                listOf(
                    dep,
                    "Total",
                    lang.formatNumber(ssBetween + ssWithin, 2),
                    lang.formatInt(dfBetween + dfWithin),
                    "—",
                    "—",
                    "—"
                )
            )
        }

        appendTable(
            title = "One-Way ANOVA (Factor: $factorName)",
            subtitle = "ONEWAY ${depVars.joinToString(" ")} BY $factorName",
            headers = listOf("Dependent Variable", "Source", "Sum of Squares", "df", "Mean Square", "F", "Sig."),
            rows = rows,
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderCorrelations(
        ds: PsppDataset,
        varNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val validVars = varNames.mapNotNull { vn -> ds.variables.find { it.name.equals(vn, true) } }
        if (validVars.size < 2) return

        val headers = listOf("Variable") + validVars.map { "${it.name} (r / p)" }
        val rows = validVars.map { vRow ->
            val cells = validVars.map { vCol ->
                if (vRow.name == vCol.name) {
                    "1.000"
                } else {
                    val (r, n) = pearsonCorrelation(ds, vRow.name, vCol.name)
                    val df = (n - 2).coerceAtLeast(1)
                    val t = if (abs(r) < 1.0) abs(r) * sqrt(df / (1.0 - r * r)) else 99.0
                    val p = twoTailedTPValue(t, df)
                    "${lang.formatNumber(r, 3)} (p=${lang.formatNumber(p, 3)})"
                }
            }
            listOf(vRow.name) + cells
        }

        appendTable(
            title = "Bivariate Pearson Correlations Matrix",
            subtitle = "CORRELATIONS /VARIABLES=${validVars.joinToString(" ") { it.name }}",
            headers = headers,
            rows = rows,
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderLinearRegression(
        ds: PsppDataset,
        depName: String,
        indepNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val depIdx = ds.variables.indexOfFirst { it.name.equals(depName, true) }
        val indepIndices = indepNames.mapNotNull { n ->
            val idx = ds.variables.indexOfFirst { it.name.equals(n, true) }
            if (idx >= 0) n to idx else null
        }
        if (depIdx < 0 || indepIndices.isEmpty()) return

        // Extract complete cases
        val completeCases = ds.rows.mapNotNull { row ->
            val y = row.getOrNull(depIdx)?.toDoubleOrNull() ?: return@mapNotNull null
            val xs = indepIndices.map { (_, idx) -> row.getOrNull(idx)?.toDoubleOrNull() ?: return@mapNotNull null }
            y to xs
        }
        val n = completeCases.size
        val k = indepIndices.size
        if (n <= k + 1) return

        // Design matrix X with intercept column 1.0
        val p = k + 1
        val xtx = Array(p) { DoubleArray(p) }
        val xty = DoubleArray(p)

        for ((y, xs) in completeCases) {
            val rowVec = DoubleArray(p) { i -> if (i == 0) 1.0 else xs[i - 1] }
            for (r in 0 until p) {
                xty[r] += rowVec[r] * y
                for (c in 0 until p) {
                    xtx[r][c] += rowVec[r] * rowVec[c]
                }
            }
        }

        val xtxInv = invertMatrix(xtx) ?: return
        val betas = DoubleArray(p) { r ->
            var s = 0.0
            for (c in 0 until p) s += xtxInv[r][c] * xty[c]
            s
        }

        val yMean = completeCases.sumOf { it.first } / n
        var ssTot = 0.0
        var ssRes = 0.0
        for ((y, xs) in completeCases) {
            var yHat = betas[0]
            for (i in 0 until k) yHat += betas[i + 1] * xs[i]
            ssTot += (y - yMean).pow(2)
            ssRes += (y - yHat).pow(2)
        }
        val ssReg = (ssTot - ssRes).coerceAtLeast(0.0)
        val rSquared = if (ssTot > 0) (ssReg / ssTot).coerceIn(0.0, 1.0) else 0.0
        val adjRSquared = 1.0 - ((1.0 - rSquared) * (n - 1.0) / (n - k - 1.0))
        val dfReg = k
        val dfRes = n - k - 1
        val msReg = ssReg / dfReg
        val msRes = ssRes / dfRes
        val fStat = if (msRes > 0) msReg / msRes else 0.0
        val fPVal = fTestPValue(fStat, dfReg, dfRes)

        appendTable(
            title = "Model Summary (Dependent Variable: $depName)",
            subtitle = "REGRESSION /DEPENDENT=$depName /METHOD=ENTER ${indepIndices.joinToString(" ") { it.first }}",
            headers = listOf("Model", "R", "R Square", "Adjusted R Square", "Std. Error of Estimate", "F", "Sig."),
            rows = listOf(
                listOf(
                    "1 (OLS)",
                    lang.formatNumber(sqrt(rSquared), 3),
                    lang.formatNumber(rSquared, 3),
                    lang.formatNumber(adjRSquared, 3),
                    lang.formatNumber(sqrt(msRes), 3),
                    lang.formatNumber(fStat, 3),
                    lang.formatNumber(fPVal, 4)
                )
            ),
            html = html,
            txt = txt,
            csv = csv
        )

        val sdY = sqrt(ssTot / (n - 1))
        val coeffRows = mutableListOf<List<String>>()
        for (j in 0 until p) {
            val name = if (j == 0) "(Constant)" else indepIndices[j - 1].first
            val b = betas[j]
            val seB = sqrt((msRes * xtxInv[j][j]).coerceAtLeast(1e-12))
            val tVal = b / seB
            val pVal = twoTailedTPValue(abs(tVal), dfRes)
            val stdBeta = if (j == 0 || sdY == 0.0) "—" else {
                val xVals = completeCases.map { it.second[j - 1] }
                val xm = xVals.average()
                val sdX = sqrt(xVals.sumOf { (it - xm).pow(2) } / (n - 1))
                lang.formatNumber(b * (sdX / sdY), 3)
            }
            val ciLow = b - 1.96 * seB
            val ciHigh = b + 1.96 * seB
            coeffRows.add(
                listOf(
                    name,
                    lang.formatNumber(b, 3),
                    lang.formatNumber(seB, 3),
                    stdBeta,
                    lang.formatNumber(tVal, 3),
                    lang.formatNumber(pVal, 4),
                    "[${lang.formatNumber(ciLow, 3)}, ${lang.formatNumber(ciHigh, 3)}]"
                )
            )
        }

        appendTable(
            title = "Regression Coefficients (Dependent Variable: $depName)",
            subtitle = "Unstandardized B, Standardized Beta, t-tests & 95% Confidence Intervals",
            headers = listOf("Predictor", "B", "Std. Error", "Beta", "t", "Sig.", "95% CI for B"),
            rows = coeffRows,
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderChiSquareGoodness(
        ds: PsppDataset,
        varNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val rows = mutableListOf<List<String>>()
        for (vName in varNames) {
            val v = ds.variables.find { it.name.equals(vName, true) } ?: continue
            val colIdx = ds.variables.indexOf(v)
            val vals = ds.rows.mapNotNull { it.getOrNull(colIdx)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val counts = vals.groupingBy { it }.eachCount()
            if (counts.size < 2) continue
            val expCount = vals.size.toDouble() / counts.size
            val chiSq = counts.values.sumOf { obs -> (obs - expCount).pow(2) / expCount }
            val df = counts.size - 1
            val pVal = chiSquarePValue(chiSq, df)
            rows.add(
                listOf(
                    v.name,
                    lang.formatInt(vals.size),
                    lang.formatInt(counts.size),
                    lang.formatNumber(chiSq, 3),
                    lang.formatInt(df),
                    lang.formatNumber(pVal, 4)
                )
            )
        }
        appendTable(
            title = "Nonparametric Chi-Square Goodness-of-Fit Test",
            subtitle = "NPAR TESTS /CHISQUARE=${varNames.joinToString(" ")} /EXPECTED=EQUAL",
            headers = listOf("Variable", "N", "Categories", "Chi-Square", "df", "Asymp. Sig."),
            rows = rows,
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun renderReliability(
        ds: PsppDataset,
        varNames: List<String>,
        lang: AppLanguage,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val indices = varNames.mapNotNull { vn ->
            val idx = ds.variables.indexOfFirst { it.name.equals(vn, true) }
            if (idx >= 0) vn to idx else null
        }
        val k = indices.size
        if (k < 2) return

        val matrix = ds.rows.mapNotNull { row ->
            indices.map { (_, idx) -> row.getOrNull(idx)?.toDoubleOrNull() ?: return@mapNotNull null }
        }
        val n = matrix.size
        if (n < 2) return

        val itemVars = (0 until k).map { col ->
            val colVals = matrix.map { it[col] }
            val m = colVals.average()
            colVals.sumOf { (it - m).pow(2) } / (n - 1)
        }
        val totalScores = matrix.map { it.sum() }
        val totalMean = totalScores.average()
        val totalVar = totalScores.sumOf { (it - totalMean).pow(2) } / (n - 1)

        val alpha = if (totalVar > 0) {
            (k.toDouble() / (k - 1.0)) * (1.0 - (itemVars.sum() / totalVar))
        } else 0.0

        appendTable(
            title = "Reliability Statistics (Cronbach's Alpha)",
            subtitle = "RELIABILITY /VARIABLES=${indices.joinToString(" ") { it.first }} /MODEL=ALPHA",
            headers = listOf("Cronbach's Alpha", "N of Items", "Valid Cases (N)", "Scale Mean", "Scale Std. Dev."),
            rows = listOf(
                listOf(
                    lang.formatNumber(alpha, 3),
                    lang.formatInt(k),
                    lang.formatInt(n),
                    lang.formatNumber(totalMean, 2),
                    lang.formatNumber(sqrt(totalVar), 2)
                )
            ),
            html = html,
            txt = txt,
            csv = csv
        )
    }

    private fun appendTable(
        title: String,
        subtitle: String,
        headers: List<String>,
        rows: List<List<String>>,
        html: MutableList<String>,
        txt: MutableList<String>,
        csv: MutableList<List<String>>
    ) {
        val sbHtml = StringBuilder()
        sbHtml.appendLine("<section class=\"pspp-card\">")
        sbHtml.appendLine("  <div class=\"pspp-card-header\">")
        sbHtml.appendLine("    <h2>${escapeHtml(title)}</h2>")
        sbHtml.appendLine("    <span class=\"pspp-cmd\">${escapeHtml(subtitle)}</span>")
        sbHtml.appendLine("  </div>")
        sbHtml.appendLine("  <div class=\"table-wrap\">")
        sbHtml.appendLine("    <table>")
        sbHtml.appendLine("      <thead><tr>${headers.joinToString("") { "<th>${escapeHtml(it)}</th>" }}</tr></thead>")
        sbHtml.appendLine("      <tbody>")
        rows.forEach { r ->
            sbHtml.appendLine("        <tr>${r.joinToString("") { "<td>${escapeHtml(it)}</td>" }}</tr>")
        }
        sbHtml.appendLine("      </tbody>")
        sbHtml.appendLine("    </table>")
        sbHtml.appendLine("  </div>")
        sbHtml.appendLine("</section>")
        html.add(sbHtml.toString())

        val sbTxt = StringBuilder()
        sbTxt.appendLine("=== $title [$subtitle] ===")
        sbTxt.appendLine(headers.joinToString(" | "))
        sbTxt.appendLine("-".repeat(72))
        rows.forEach { r -> sbTxt.appendLine(r.joinToString(" | ")) }
        txt.add(sbTxt.toString())

        csv.add(listOf("# $title"))
        csv.add(headers)
        csv.addAll(rows)
        csv.add(emptyList())
    }

    private fun wrapHtml(sections: List<String>, lang: AppLanguage, datasetName: String): String {
        val dir = if (lang == AppLanguage.AR) "rtl" else "ltr"
        return """
            <!DOCTYPE html>
            <html lang="${lang.code}" dir="$dir">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
              <title>GNU PSPP 2.1.2 Output — $datasetName</title>
              <style>
                :root {
                  color-scheme: light dark;
                }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  margin: 0;
                  padding: 12px;
                  background: #0F172A;
                  color: #F1F5F9;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #F8FAFC; color: #0F172A; }
                  .pspp-card { background: #FFFFFF !important; border-color: #CBD5E1 !important; }
                  th { background: #E2E8F0 !important; color: #0F172A !important; }
                  td { border-color: #E2E8F0 !important; }
                }
                .pspp-banner {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  padding: 10px 14px;
                  margin-bottom: 14px;
                  border-radius: 10px;
                  background: linear-gradient(135deg, #0C4A6E, #1E293B);
                  color: #F8FAFC;
                  font-size: 13px;
                }
                .pspp-card {
                  background: #1E293B;
                  border: 1px solid #334155;
                  border-radius: 12px;
                  margin-bottom: 16px;
                  overflow: hidden;
                }
                .pspp-card-header {
                  padding: 12px 14px;
                  border-bottom: 1px solid #334155;
                }
                .pspp-card-header h2 {
                  margin: 0 0 4px 0;
                  font-size: 15px;
                  color: #38BDF8;
                }
                .pspp-cmd {
                  font-family: monospace;
                  font-size: 11px;
                  color: #94A3B8;
                }
                .table-wrap {
                  overflow-x: auto;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                  font-size: 12.5px;
                }
                th, td {
                  padding: 8px 10px;
                  text-align: start;
                  border-bottom: 1px solid #334155;
                  white-space: nowrap;
                }
                th {
                  background: #0F172A;
                  color: #38BDF8;
                  font-weight: 600;
                }
                tr:last-child td {
                  border-bottom: none;
                }
                .approx-warning {
                  padding: 10px 14px;
                  margin-bottom: 12px;
                  border-radius: 10px;
                  background: #7C2D12;
                  border: 1px solid #F97316;
                  color: #FFEDD5;
                  font-weight: 700;
                  font-size: 13px;
                }
              </style>
            </head>
            <body>
              <div class="approx-warning">$APPROXIMATION_BANNER</div>
              <div class="pspp-banner">
                <strong>GNU PSPP 2.1.2 Output Driver (HTML)</strong>
                <span>Dataset: ${escapeHtml(datasetName)}</span>
              </div>
              ${sections.joinToString("\n")}
            </body>
            </html>
        """.trimIndent()
    }

    private fun splitStatements(script: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        val lines = script.replace("\r\n", "\n").split('\n')
        val current = StringBuilder()
        var startLine = 1
        var inData = false

        for (i in lines.indices) {
            val l = lines[i].trim()
            if (l.isEmpty() || l.startsWith("*")) continue
            if (l.uppercase().startsWith("BEGIN DATA")) {
                inData = true
                continue
            }
            if (l.uppercase().startsWith("END DATA")) {
                inData = false
                continue
            }
            if (inData) continue
            if (current.isEmpty()) startLine = i + 1
            current.append(" ").append(l)
            if (l.endsWith(".")) {
                result.add(startLine to current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            result.add(startLine to current.toString())
        }
        return result
    }

    private fun extractVariablesList(stmt: String, key: String, ds: PsppDataset): List<String> {
        val regex = Regex("$key\\s*=\\s*([^/.]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(stmt)?.groupValues?.get(1)?.trim().orEmpty()
        if (match.isEmpty()) return ds.variables.map { it.name }.take(6)
        val tokens = match.split(Regex("[\\s,]+")).filter { it.isNotBlank() && !it.equals("ALL", true) }
        return tokens.ifEmpty { ds.variables.map { it.name } }
    }

    private fun numericColumn(ds: PsppDataset, varName: String): List<Double> {
        val idx = ds.variables.indexOfFirst { it.name.equals(varName, true) }
        if (idx < 0) return emptyList()
        val missing = ds.variables[idx].missingValues.toSet()
        return ds.rows.mapNotNull { r ->
            val raw = r.getOrNull(idx)?.trim().orEmpty()
            if (raw.isEmpty() || raw in missing) null else raw.toDoubleOrNull()
        }
    }

    private fun pearsonCorrelation(ds: PsppDataset, var1: String, var2: String): Pair<Double, Int> {
        val i1 = ds.variables.indexOfFirst { it.name.equals(var1, true) }
        val i2 = ds.variables.indexOfFirst { it.name.equals(var2, true) }
        if (i1 < 0 || i2 < 0) return 0.0 to 0
        val pairs = ds.rows.mapNotNull { r ->
            val a = r.getOrNull(i1)?.toDoubleOrNull()
            val b = r.getOrNull(i2)?.toDoubleOrNull()
            if (a != null && b != null) a to b else null
        }
        if (pairs.size < 2) return 0.0 to pairs.size
        val m1 = pairs.sumOf { it.first } / pairs.size
        val m2 = pairs.sumOf { it.second } / pairs.size
        val num = pairs.sumOf { (it.first - m1) * (it.second - m2) }
        val den = sqrt(pairs.sumOf { (it.first - m1).pow(2) } * pairs.sumOf { (it.second - m2).pow(2) })
        return (if (den > 0) (num / den).coerceIn(-1.0, 1.0) else 0.0) to pairs.size
    }

    private fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val aug = Array(n) { r ->
            DoubleArray(2 * n) { c ->
                if (c < n) matrix[r][c] else if (c - n == r) 1.0 else 0.0
            }
        }
        for (i in 0 until n) {
            var pivot = i
            for (r in i + 1 until n) {
                if (abs(aug[r][i]) > abs(aug[pivot][i])) pivot = r
            }
            if (abs(aug[pivot][i]) < 1e-12) return null
            val tmp = aug[i]
            aug[i] = aug[pivot]
            aug[pivot] = tmp
            val div = aug[i][i]
            for (c in 0 until 2 * n) aug[i][c] /= div
            for (r in 0 until n) {
                if (r != i) {
                    val factor = aug[r][i]
                    for (c in 0 until 2 * n) aug[r][c] -= factor * aug[i][c]
                }
            }
        }
        return Array(n) { r -> DoubleArray(n) { c -> aug[r][c + n] } }
    }

    private fun twoTailedTPValue(t: Double, df: Int): Double {
        if (df <= 0) return 1.0
        val z = t * (1.0 - 1.0 / (4.0 * df)) / sqrt(1.0 + (t * t) / (2.0 * df))
        return (2.0 * (1.0 - normalCdf(abs(z)))).coerceIn(0.0001, 1.0)
    }

    private fun chiSquarePValue(chiSq: Double, df: Int): Double {
        if (chiSq <= 0.0 || df <= 0) return 1.0
        val k = df.toDouble()
        val z = ((chiSq / k).pow(1.0 / 3.0) - (1.0 - 2.0 / (9.0 * k))) / sqrt(2.0 / (9.0 * k))
        return (1.0 - normalCdf(z)).coerceIn(0.0001, 1.0)
    }

    private fun fTestPValue(f: Double, df1: Int, df2: Int): Double {
        if (f <= 0.0 || df1 <= 0 || df2 <= 0) return 1.0
        val chiApprox = f * df1
        return chiSquarePValue(chiApprox, df1)
    }

    private fun normalCdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val d = 0.3989423 * exp(-x * x / 2.0)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (x > 0) 1.0 - p else p
    }

    private fun formatCsv(rows: List<List<String>>): String {
        val stampedRows = listOf(listOf("# $APPROXIMATION_BANNER")) + rows
        return stampedRows.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                if (cell.contains(',') || cell.contains('"')) "\"${cell.replace("\"", "\"\"")}\"" else cell
            }
        }
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
