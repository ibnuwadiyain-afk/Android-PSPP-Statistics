package org.pspp.mobile.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.pspp.mobile.engine.PsppAssetExtractor
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.engine.PsppStatisticalEvaluator
import org.pspp.mobile.engine.PsppSyntaxGenerator
import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.AnalysisProcedure
import org.pspp.mobile.model.AppLanguage
import org.pspp.mobile.model.AppScreen
import org.pspp.mobile.model.CsvImportPreviewState
import org.pspp.mobile.model.MeasureLevel
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.PsppRunResult
import org.pspp.mobile.model.PsppVariable
import org.pspp.mobile.model.RecentFileItem
import org.pspp.mobile.model.SyntaxDiagnostic
import org.pspp.mobile.model.VariableType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PsppUiState(
    val currentScreen: AppScreen = AppScreen.SYNTAX,
    val language: AppLanguage = AppLanguage.EN,
    val engineBanner: String = "",
    val isNativeJniLoaded: Boolean = false,
    val syntaxText: String = "",
    val selectionStartLine: Int = 1,
    val selectionEndLine: Int = 1,
    val diagnostics: List<SyntaxDiagnostic> = emptyList(),
    val dataset: PsppDataset = PsppDataset("clinical_trial", variables = emptyList(), rows = emptyList()),
    val isVariableView: Boolean = false,
    val isRunning: Boolean = false,
    val elapsedRunMs: Long = 0L,
    val latestResult: PsppRunResult? = null,
    val activeOutputTab: Int = 0, // 0 = HTML, 1 = Plain Text, 2 = CSV
    val recentFiles: List<RecentFileItem> = emptyList(),
    val csvPreview: CsvImportPreviewState? = null,
    val selectedProcedure: AnalysisProcedure? = null,
    val patchesMap: Map<String, String> = emptyMap(),
    val gplLicenseText: String = "",
    val statusBannerMessage: String? = null
)

class PsppViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PsppUiState())
    val uiState: StateFlow<PsppUiState> = _uiState.asStateFlow()

    private var extractedEnv: PsppAssetExtractor.ExtractedEnvironment? = null
    private var runJob: Job? = null
    private var timerJob: Job? = null

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        val context = getApplication<Application>()
        val env = PsppBridge.init(context)
        extractedEnv = env

        val defaultScriptFile = File(env.samplesDir, "descriptives.sps")
        val initialSyntax = if (defaultScriptFile.exists()) {
            defaultScriptFile.readText()
        } else {
            PsppSyntaxGenerator.generateDatasetSyntax(env.defaultClinicalDataset, includeInlineData = false)
        }

        val patches = env.patchesDir.listFiles()
            ?.sortedBy { it.name }
            ?.associate { it.name to it.readText() }
            .orEmpty()

        val gplFile = File(env.dataDir, "COPYING")
        val gplText = if (gplFile.exists()) gplFile.readText() else ""

        val initialRecent = listOf(
            RecentFileItem(
                name = "clinical_trial.sav",
                uriOrPath = File(env.samplesDir, "clinical_trial.sav").absolutePath,
                format = "SAV",
                timestampMs = System.currentTimeMillis() - 120_000L,
                sizeBytes = File(env.samplesDir, "clinical_trial.sav").length()
            ),
            RecentFileItem(
                name = "clinical_trial.csv",
                uriOrPath = File(env.samplesDir, "clinical_trial.csv").absolutePath,
                format = "CSV",
                timestampMs = System.currentTimeMillis() - 240_000L,
                sizeBytes = File(env.samplesDir, "clinical_trial.csv").length()
            ),
            RecentFileItem(
                name = "descriptives.sps",
                uriOrPath = File(env.samplesDir, "descriptives.sps").absolutePath,
                format = "SPS",
                timestampMs = System.currentTimeMillis() - 360_000L,
                sizeBytes = defaultScriptFile.length()
            )
        )

        val initialDiagnostics = PsppStatisticalEvaluator.validateSyntax(initialSyntax, env.defaultClinicalDataset)
        val totalLines = initialSyntax.lines().size.coerceAtLeast(1)

        _uiState.update {
            it.copy(
                engineBanner = PsppBridge.getEngineVersionBanner(),
                isNativeJniLoaded = PsppBridge.isNativeLoaded,
                syntaxText = initialSyntax,
                selectionStartLine = 1,
                selectionEndLine = minOf(totalLines, 10),
                diagnostics = initialDiagnostics,
                dataset = env.defaultClinicalDataset,
                recentFiles = initialRecent,
                patchesMap = patches,
                gplLicenseText = gplText
            )
        }

        // Run initial sample script so Output Viewer is populated immediately on launch
        runSyntaxScript(initialSyntax, navigateToOutput = false)
    }

    fun selectScreen(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen, statusBannerMessage = null) }
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
        // Re-render latest output in the newly selected language so localized tables update immediately
        val currentScript = _uiState.value.syntaxText
        if (currentScript.isNotBlank() && !_uiState.value.isRunning) {
            runSyntaxScript(currentScript, navigateToOutput = false, overrideLanguage = language)
        }
    }

    fun updateSyntaxText(newText: String) {
        val diags = PsppStatisticalEvaluator.validateSyntax(newText, _uiState.value.dataset)
        val maxLine = newText.lines().size.coerceAtLeast(1)
        _uiState.update {
            it.copy(
                syntaxText = newText,
                diagnostics = diags,
                selectionStartLine = it.selectionStartLine.coerceIn(1, maxLine),
                selectionEndLine = it.selectionEndLine.coerceIn(1, maxLine)
            )
        }
    }

    fun updateSelectionLines(startLine: Int, endLine: Int) {
        val maxLine = _uiState.value.syntaxText.lines().size.coerceAtLeast(1)
        val s = startLine.coerceIn(1, maxLine)
        val e = endLine.coerceIn(s, maxLine)
        _uiState.update { it.copy(selectionStartLine = s, selectionEndLine = e) }
    }

    fun loadSampleScript(sampleFileName: String) {
        val env = extractedEnv ?: return
        val file = File(env.samplesDir, sampleFileName)
        if (file.exists()) {
            val content = file.readText()
            updateSyntaxText(content)
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.SYNTAX,
                    statusBannerMessage = "Loaded bundled sample: $sampleFileName"
                )
            }
        }
    }

    fun runAllSyntax() {
        runSyntaxScript(_uiState.value.syntaxText, navigateToOutput = true)
    }

    fun runSelectedSyntax() {
        val state = _uiState.value
        val lines = state.syntaxText.lines()
        if (lines.isEmpty()) return
        val sIdx = (state.selectionStartLine - 1).coerceIn(0, lines.lastIndex)
        val eIdx = (state.selectionEndLine - 1).coerceIn(sIdx, lines.lastIndex)
        val selectedBlock = lines.subList(sIdx, eIdx + 1).joinToString("\n")
        val normalized = if (selectedBlock.trim().endsWith(".")) selectedBlock else "$selectedBlock."
        runSyntaxScript(normalized, navigateToOutput = true)
    }

    fun runSyntaxScript(
        script: String,
        navigateToOutput: Boolean = true,
        overrideLanguage: AppLanguage? = null
    ) {
        if (_uiState.value.isRunning) return
        val context = getApplication<Application>()
        val lang = overrideLanguage ?: _uiState.value.language

        _uiState.update {
            it.copy(
                isRunning = true,
                elapsedRunMs = 0L,
                statusBannerMessage = null
            )
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                delay(100)
                _uiState.update { it.copy(elapsedRunMs = System.currentTimeMillis() - start) }
            }
        }

        runJob = viewModelScope.launch {
            val (result, updatedDataset) = PsppBridge.runSyntax(
                context = context,
                scriptContent = script,
                activeDataset = _uiState.value.dataset,
                format = "html",
                language = lang
            )
            timerJob?.cancel()
            _uiState.update {
                it.copy(
                    isRunning = false,
                    elapsedRunMs = result.durationMs,
                    latestResult = result,
                    dataset = updatedDataset,
                    diagnostics = result.diagnostics,
                    currentScreen = if (navigateToOutput && !result.cancelled) AppScreen.OUTPUT else it.currentScreen,
                    statusBannerMessage = if (result.cancelled) "Execution cancelled."
                    else "Execution finished in ${result.durationMs} ms."
                )
            }
        }
    }

    fun cancelRun() {
        PsppBridge.cancel()
        runJob?.cancel()
        timerJob?.cancel()
        _uiState.update {
            it.copy(
                isRunning = false,
                statusBannerMessage = "Execution cancelled by user."
            )
        }
    }

    // Data & Variable View Operations
    fun setDataEditorMode(isVariableView: Boolean) {
        _uiState.update { it.copy(isVariableView = isVariableView) }
    }

    fun updateCellValue(rowIndex: Int, colIndex: Int, newValue: String) {
        val ds = _uiState.value.dataset
        if (rowIndex !in ds.rows.indices || colIndex !in ds.variables.indices) return
        val updatedRows = ds.rows.mapIndexed { rIdx, row ->
            if (rIdx == rowIndex) {
                row.mapIndexed { cIdx, cell -> if (cIdx == colIndex) newValue else cell }
            } else row
        }
        val newDs = ds.copy(rows = updatedRows, modified = true)
        persistActiveDatasetBack(newDs)
    }

    fun addCaseRow() {
        val ds = _uiState.value.dataset
        val nextId = (ds.rows.size + 1).toString()
        val newRow = ds.variables.mapIndexed { idx, v ->
            if (idx == 0 && v.name.equals("id", true)) nextId
            else if (v.type == VariableType.STRING) ""
            else "0"
        }
        val newDs = ds.copy(rows = ds.rows + listOf(newRow), modified = true)
        persistActiveDatasetBack(newDs)
    }

    fun deleteCaseRow(rowIndex: Int) {
        val ds = _uiState.value.dataset
        if (rowIndex !in ds.rows.indices) return
        val updatedRows = ds.rows.filterIndexed { idx, _ -> idx != rowIndex }
        val newDs = ds.copy(rows = updatedRows, modified = true)
        persistActiveDatasetBack(newDs)
    }

    fun addVariable(variable: PsppVariable) {
        val ds = _uiState.value.dataset
        val sanitized = SavBinaryCodec.sanitizeVariableName(variable.name, ds.variables.size)
        val uniqueName = if (ds.variables.any { it.name.equals(sanitized, true) }) {
            "${sanitized}_${ds.variables.size + 1}"
        } else sanitized
        val finalVar = variable.copy(name = uniqueName)
        val defaultCell = if (finalVar.type == VariableType.STRING) "" else "0"
        val updatedRows = ds.rows.map { it + defaultCell }
        val newDs = ds.copy(
            variables = ds.variables + finalVar,
            rows = updatedRows,
            modified = true
        )
        persistActiveDatasetBack(newDs)
    }

    fun updateVariable(varIndex: Int, updated: PsppVariable) {
        val ds = _uiState.value.dataset
        if (varIndex !in ds.variables.indices) return
        val newVars = ds.variables.mapIndexed { idx, v -> if (idx == varIndex) updated else v }
        val newDs = ds.copy(variables = newVars, modified = true)
        persistActiveDatasetBack(newDs)
    }

    fun deleteVariable(varIndex: Int) {
        val ds = _uiState.value.dataset
        if (varIndex !in ds.variables.indices || ds.variables.size <= 1) return
        val newVars = ds.variables.filterIndexed { idx, _ -> idx != varIndex }
        val newRows = ds.rows.map { row -> row.filterIndexed { idx, _ -> idx != varIndex } }
        val newDs = ds.copy(variables = newVars, rows = newRows, modified = true)
        persistActiveDatasetBack(newDs)
    }

    private fun persistActiveDatasetBack(newDs: PsppDataset) {
        _uiState.update { it.copy(dataset = newDs) }
        viewModelScope.launch(Dispatchers.IO) {
            val env = extractedEnv ?: return@launch
            val savTarget = File(env.samplesDir, "${newDs.name}.sav")
            SavBinaryCodec.saveDatasetToSavFile(newDs, savTarget)
        }
    }

    fun syncDatasetChangesToSyntax() {
        val generated = PsppSyntaxGenerator.generateDatasetSyntax(_uiState.value.dataset, includeInlineData = true)
        updateSyntaxText(generated)
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.SYNTAX,
                statusBannerMessage = "Synchronized dataset dictionary & cases into PSPP syntax editor."
            )
        }
    }

    // Analysis Procedure Dialogs
    fun openProcedureDialog(procedure: AnalysisProcedure?) {
        _uiState.update { it.copy(selectedProcedure = procedure) }
    }

    fun setOutputTab(tabIndex: Int) {
        _uiState.update { it.copy(activeOutputTab = tabIndex) }
    }

    // File I/O & Storage Access Framework
    fun openLocalFilePath(path: String) {
        val file = File(path)
        if (!file.exists()) return
        viewModelScope.launch {
            val ext = file.extension.lowercase()
            if (ext == "sps") {
                val content = withContext(Dispatchers.IO) { file.readText() }
                updateSyntaxText(content)
                addRecentFile(file.name, file.absolutePath, "SPS", file.length())
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.SYNTAX,
                        statusBannerMessage = "Opened syntax file ${file.name}"
                    )
                }
            } else {
                val ds = PsppBridge.readDatasetFile(file)
                addRecentFile(file.name, file.absolutePath, ext.uppercase(), file.length())
                _uiState.update {
                    it.copy(
                        dataset = ds,
                        currentScreen = AppScreen.DATA,
                        statusBannerMessage = "Loaded dataset ${file.name} (${ds.caseCount} cases, ${ds.variableCount} variables)"
                    )
                }
            }
        }
    }

    fun handleSafOpenUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (displayName, size) = queryUriMetadata(context, uri)
                val ext = displayName.substringAfterLast('.', "").lowercase()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch

                when (ext) {
                    "sps" -> {
                        val text = bytes.toString(Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            updateSyntaxText(text)
                            addRecentFile(displayName, uri.toString(), "SPS", size)
                            _uiState.update {
                                it.copy(
                                    currentScreen = AppScreen.SYNTAX,
                                    statusBannerMessage = "Opened $displayName via Storage Access Framework"
                                )
                            }
                        }
                    }
                    "sav", "zsav", "por" -> {
                        val tempFile = File(context.cacheDir, displayName)
                        tempFile.writeBytes(bytes)
                        val ds = PsppBridge.readDatasetFile(tempFile)
                        withContext(Dispatchers.Main) {
                            addRecentFile(displayName, tempFile.absolutePath, ext.uppercase(), size)
                            _uiState.update {
                                it.copy(
                                    dataset = ds,
                                    currentScreen = AppScreen.DATA,
                                    statusBannerMessage = "Loaded $displayName (${ds.caseCount} cases)"
                                )
                            }
                        }
                    }
                    else -> {
                        // CSV or Delimited Text -> open column-type preview
                        val text = bytes.toString(Charsets.UTF_8)
                        val defaultDelim = if (ext == "tsv" || text.lines().firstOrNull()?.contains('\t') == true) '\t' else ','
                        val preview = SavBinaryCodec.previewDelimitedText(displayName, text, defaultDelim, true)
                        withContext(Dispatchers.Main) {
                            addRecentFile(displayName, uri.toString(), ext.uppercase().ifEmpty { "CSV" }, size)
                            _uiState.update {
                                it.copy(
                                    csvPreview = preview,
                                    currentScreen = AppScreen.FILES,
                                    statusBannerMessage = "Inspect column types for $displayName before importing"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusBannerMessage = "Failed to open file: ${e.message}") }
                }
            }
        }
    }

    fun updateCsvPreviewOptions(delimiter: Char, hasHeader: Boolean) {
        val current = _uiState.value.csvPreview ?: return
        val updated = SavBinaryCodec.previewDelimitedText(
            fileName = current.fileName,
            rawContent = current.rawContent,
            delimiter = delimiter,
            hasHeader = hasHeader
        )
        _uiState.update { it.copy(csvPreview = updated) }
    }

    fun applyCsvPreviewImport() {
        val preview = _uiState.value.csvPreview ?: return
        val ds = SavBinaryCodec.parseCsvToDataset(
            fileName = preview.fileName,
            rawContent = preview.rawContent,
            delimiter = preview.delimiter,
            hasHeader = preview.hasHeader
        )
        _uiState.update {
            it.copy(
                dataset = ds,
                csvPreview = null,
                currentScreen = AppScreen.DATA,
                statusBannerMessage = "Imported ${ds.name} (${ds.caseCount} rows, ${ds.variableCount} variables)"
            )
        }
    }

    fun dismissCsvPreview() {
        _uiState.update { it.copy(csvPreview = null) }
    }

    fun openSampleCsvInPreview() {
        val env = extractedEnv ?: return
        val file = File(env.samplesDir, "clinical_trial.csv")
        if (file.exists()) {
            val raw = file.readText()
            val preview = SavBinaryCodec.previewDelimitedText("clinical_trial.csv", raw, ',', true)
            _uiState.update { it.copy(csvPreview = preview) }
        }
    }

    fun saveToSafUri(context: Context, uri: Uri, format: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val stamp = PsppBridge.APPROXIMATION_BANNER
                val isFallback = !state.isNativeJniLoaded
                val bytes: ByteArray = when (format.lowercase()) {
                    "sps" -> state.syntaxText.toByteArray(Charsets.UTF_8)
                    "sav" -> SavBinaryCodec.writeSavBytes(state.dataset)
                    "csv_data" -> {
                        val rawCsv = SavBinaryCodec.exportDatasetToCsv(state.dataset)
                        val stamped = if (isFallback && !rawCsv.contains(stamp)) "# $stamp\n$rawCsv" else rawCsv
                        stamped.toByteArray(Charsets.UTF_8)
                    }
                    "html" -> {
                        val rawHtml = state.latestResult?.htmlContent ?: ""
                        val stamped = if (isFallback && !rawHtml.contains(stamp)) "<!-- $stamp -->\n<p><strong>$stamp</strong></p>\n$rawHtml" else rawHtml
                        stamped.toByteArray(Charsets.UTF_8)
                    }
                    "txt" -> {
                        val rawTxt = state.latestResult?.plainTextContent ?: ""
                        val stamped = if (isFallback && !rawTxt.contains(stamp)) "$stamp\n\n$rawTxt" else rawTxt
                        stamped.toByteArray(Charsets.UTF_8)
                    }
                    "csv_out" -> {
                        val rawCsv = state.latestResult?.csvContent ?: ""
                        val stamped = if (isFallback && !rawCsv.contains(stamp)) "# $stamp\n$rawCsv" else rawCsv
                        stamped.toByteArray(Charsets.UTF_8)
                    }
                    else -> {
                        val rawCsv = SavBinaryCodec.exportDatasetToCsv(state.dataset)
                        val stamped = if (isFallback && !rawCsv.contains(stamp)) "# $stamp\n$rawCsv" else rawCsv
                        stamped.toByteArray(Charsets.UTF_8)
                    }
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                val (name, _) = queryUriMetadata(context, uri)
                withContext(Dispatchers.Main) {
                    addRecentFile(name, uri.toString(), format.uppercase(), bytes.size.toLong())
                    _uiState.update {
                        it.copy(statusBannerMessage = "Saved $name (${bytes.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusBannerMessage = "Save failed: ${e.message}") }
                }
            }
        }
    }

    private fun addRecentFile(name: String, uriOrPath: String, format: String, sizeBytes: Long) {
        val item = RecentFileItem(
            name = name,
            uriOrPath = uriOrPath,
            format = format,
            timestampMs = System.currentTimeMillis(),
            sizeBytes = sizeBytes
        )
        _uiState.update { state ->
            val filtered = state.recentFiles.filterNot { it.name == name && it.uriOrPath == uriOrPath }
            state.copy(recentFiles = (listOf(item) + filtered).take(12))
        }
    }

    private fun queryUriMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "pspp_file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}
