package org.pspp.mobile.engine

import android.content.Context
import org.pspp.mobile.BuildConfig
import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.AppLanguage
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.PsppRunResult
import org.pspp.mobile.model.SyntaxDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI wrapper and execution coordinator for GNU PSPP 2.1.2.
 *
 * Contract:
 *   - Attempts to load `libpspp_jni.so` from `app/src/main/jniLibs/<abi>/libpspp_jni.so`.
 *   - When `isNativeLoaded == true`, delegates `init`, `runSyntax`, `cancel`, and `readDataset`
 *     directly to the C JNI bridge (`pspp_jni.c` -> `libpspp` / `libpspp-core`).
 *   - When `isNativeLoaded == false`:
 *       - If `BuildConfig.ALLOW_FALLBACK == false` (default for release builds or when built with
 *         `-PallowFallback=false`), refuses to run the fallback engine so a release APK can never
 *         silently produce non-PSPP results.
 *       - If `BuildConfig.ALLOW_FALLBACK == true`, runs [PsppStatisticalEvaluator] and stamps every
 *         HTML, plain text, and CSV output with [PsppStatisticalEvaluator.APPROXIMATION_BANNER].
 *   - Enforces one run at a time via [executionMutex] with a clean working directory per run.
 */
object PsppBridge {

    const val APPROXIMATION_BANNER = PsppStatisticalEvaluator.APPROXIMATION_BANNER

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("pspp_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    val isFallbackAllowed: Boolean
        get() = BuildConfig.ALLOW_FALLBACK

    private val executionMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private var extractedEnv: PsppAssetExtractor.ExtractedEnvironment? = null

    // Native JNI methods implemented in app/src/main/cpp/pspp_jni.c
    private external fun nativeInit(dataDir: String, tmpDir: String, localeDir: String): Boolean
    private external fun nativeGetVersion(): String
    private external fun nativeRunSyntax(scriptPath: String, outputDir: String, format: String): String
    private external fun nativeCancel()
    private external fun nativeReadDataset(filePath: String, maxRows: Int): String

    fun init(context: Context): PsppAssetExtractor.ExtractedEnvironment {
        val env = PsppAssetExtractor.ensureExtracted(context)
        extractedEnv = env
        if (isNativeLoaded) {
            try {
                nativeInit(
                    env.dataDir.absolutePath,
                    env.tmpDir.absolutePath,
                    env.localeDir.absolutePath
                )
            } catch (_: Throwable) {
                // Keep extracted environment active if JNI symbol is unavailable
            }
        }
        return env
    }

    fun getEngineVersionBanner(): String {
        return if (isNativeLoaded) {
            try {
                nativeGetVersion()
            } catch (_: Throwable) {
                "GNU PSPP 2.1.2 (JNI Native: libpspp_jni.so)"
            }
        } else {
            APPROXIMATION_BANNER
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        if (isNativeLoaded) {
            try {
                nativeCancel()
            } catch (_: Throwable) {
                // Ignored
            }
        }
    }

    /**
     * Runs PSPP syntax in a background worker thread, one run at a time, inside
     * a clean working directory under `cacheDir/pspp_runs/run_<timestamp>`.
     */
    suspend fun runSyntax(
        context: Context,
        scriptContent: String,
        activeDataset: PsppDataset,
        format: String = "html",
        language: AppLanguage = AppLanguage.EN
    ): Pair<PsppRunResult, PsppDataset> = withContext(Dispatchers.IO) {
        executionMutex.withLock {
            cancelRequested.set(false)
            val currentEnv = extractedEnv
            val env = if (currentEnv != null && File(currentEnv.samplesDir, "clinical_trial.csv").exists()) {
                currentEnv
            } else {
                init(context)
            }

            // Create a clean working directory for this run
            val runsParent = File(context.cacheDir, "pspp_runs").apply { mkdirs() }
            val runDir = File(runsParent, "run_${System.currentTimeMillis()}").apply { mkdirs() }

            // Write active dataset and syntax script into the clean working directory
            val activeCsvFile = File(runDir, "${activeDataset.name}.csv")
            activeCsvFile.writeText(SavBinaryCodec.exportDatasetToCsv(activeDataset))
            val activeSavFile = File(runDir, "${activeDataset.name}.sav")
            SavBinaryCodec.saveDatasetToSavFile(activeDataset, activeSavFile)

            // Also copy sample clinical_trial.csv into runDir so relative GET DATA /FILE='clinical_trial.csv' works
            val sampleCsv = File(env.samplesDir, "clinical_trial.csv")
            if (sampleCsv.exists()) {
                sampleCsv.copyTo(File(runDir, "clinical_trial.csv"), overwrite = true)
            }

            val scriptFile = File(runDir, "syntax.sps")
            scriptFile.writeText(scriptContent)

            val startMs = System.currentTimeMillis()
            if (isNativeLoaded) {
                try {
                    val jsonStr = nativeRunSyntax(
                        scriptFile.absolutePath,
                        runDir.absolutePath,
                        format
                    )
                    val json = JSONObject(jsonStr)
                    val exitCode = json.optInt("exitCode", -1)
                    val htmlFile = File(json.optString("htmlPath", File(runDir, "output.html").absolutePath))
                    val txtFile = File(json.optString("txtPath", File(runDir, "output.txt").absolutePath))
                    val csvFile = File(json.optString("csvPath", File(runDir, "output.csv").absolutePath))
                    val logFile = File(json.optString("logPath", File(runDir, "diagnostics.log").absolutePath))

                    if (exitCode >= 0 && (htmlFile.exists() || txtFile.exists())) {
                        val diagnostics = parseNativeDiagnostics(logFile)
                        val result = PsppRunResult(
                            exitCode = exitCode,
                            cancelled = json.optBoolean("cancelled", false),
                            htmlContent = if (htmlFile.exists()) htmlFile.readText() else "",
                            plainTextContent = if (txtFile.exists()) txtFile.readText() else "",
                            csvContent = if (csvFile.exists()) csvFile.readText() else "",
                            diagnostics = diagnostics,
                            durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                            usedNativeJni = true,
                            outputDir = runDir.absolutePath
                        )
                        return@withLock Pair(result, activeDataset)
                    }
                } catch (_: Throwable) {
                    // Fall through only if fallback is allowed
                }
            }

            if (!isFallbackAllowed) {
                val msg = "Native library libpspp_jni.so is not loaded and fallback mode is disabled (-PallowFallback=false)."
                val errHtml = """
                    <!DOCTYPE html>
                    <html><body style="font-family:sans-serif;background:#0F172A;color:#FCA5A5;padding:16px;">
                    <h3>GNU PSPP 2.1.2 Native Engine Required</h3>
                    <p>$msg</p>
                    </body></html>
                """.trimIndent()
                return@withLock Pair(
                    PsppRunResult(
                        exitCode = -1,
                        cancelled = false,
                        htmlContent = errHtml,
                        plainTextContent = msg,
                        csvContent = "# $msg",
                        diagnostics = listOf(SyntaxDiagnostic(1, "ERROR", msg)),
                        durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                        usedNativeJni = false,
                        outputDir = runDir.absolutePath
                    ),
                    activeDataset
                )
            }

            PsppStatisticalEvaluator.evaluateScript(
                script = scriptContent,
                initialDataset = activeDataset,
                samplesDir = env.samplesDir,
                outputDir = runDir,
                language = language,
                isCancelled = { cancelRequested.get() }
            )
        }
    }

    /**
     * Reads a `.sav`, `.zsav`, `.por`, or `.csv` file into a [PsppDataset] for the Data & Variable grids.
     */
    suspend fun readDatasetFile(
        file: File,
        maxRows: Int = 5000
    ): PsppDataset = withContext(Dispatchers.IO) {
        if (isNativeLoaded && (file.name.endsWith(".sav", true) || file.name.endsWith(".zsav", true) || file.name.endsWith(".por", true))) {
            try {
                val jsonStr = nativeReadDataset(file.absolutePath, maxRows)
                val json = JSONObject(jsonStr)
                if (json.optInt("exitCode", -1) == 0) {
                    val csvPath = json.optString("csvPath")
                    val extractedCsv = File(csvPath)
                    if (extractedCsv.exists()) {
                        val csvDataset = SavBinaryCodec.parseCsvToDataset(
                            file.nameWithoutExtension,
                            extractedCsv.readText()
                        ).copy(sourcePath = file.absolutePath)
                        if (file.name.endsWith(".sav", true)) {
                            // Merge dictionary labels/formats from binary .sav header with engine-verified case rows
                            return@withContext try {
                                val savDict = SavBinaryCodec.readSavBytes(file.readBytes(), file.nameWithoutExtension)
                                savDict.copy(
                                    rows = if (csvDataset.rows.isNotEmpty()) csvDataset.rows else savDict.rows,
                                    sourcePath = file.absolutePath
                                )
                            } catch (_: Throwable) {
                                csvDataset
                            }
                        }
                        return@withContext csvDataset
                    }
                }
            } catch (_: Throwable) {
                // Fall back to SavBinaryCodec if allowed
            }
        }

        if (!isNativeLoaded && !isFallbackAllowed) {
            throw IllegalStateException("Native library libpspp_jni.so is not loaded and fallback mode is disabled (-PallowFallback=false).")
        }

        if (file.name.endsWith(".sav", true)) {
            SavBinaryCodec.readSavBytes(file.readBytes(), file.nameWithoutExtension)
                .copy(sourcePath = file.absolutePath)
        } else {
            val delimiter = if (file.name.endsWith(".tsv", true) || file.name.endsWith(".txt", true)) '\t' else ','
            SavBinaryCodec.parseCsvToDataset(
                fileName = file.nameWithoutExtension,
                rawContent = file.readText(),
                delimiter = delimiter,
                hasHeader = true
            ).copy(sourcePath = file.absolutePath)
        }
    }

    private fun parseNativeDiagnostics(logFile: File): List<SyntaxDiagnostic> {
        if (!logFile.exists()) return emptyList()
        val diagnostics = mutableListOf<SyntaxDiagnostic>()
        val lineRegex = Regex(":(\\d+)(?:\\.\\d+-\\d+)?\\s*:\\s*(error|warning)\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
        logFile.forEachLine { line ->
            val match = lineRegex.find(line)
            if (match != null) {
                diagnostics.add(
                    SyntaxDiagnostic(
                        line = match.groupValues[1].toIntOrNull() ?: 1,
                        severity = match.groupValues[2].uppercase(),
                        message = match.groupValues[3].trim()
                    )
                )
            }
        }
        return diagnostics
    }
}
