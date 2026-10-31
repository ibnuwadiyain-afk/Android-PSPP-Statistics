package org.pspp.mobile.engine

import android.content.Context
import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.MeasureLevel
import org.pspp.mobile.model.PsppDataset
import java.io.File

/**
 * Extracts bundled GNU PSPP 2.1.2 data files, compiled GNU gettext catalogs (.mo),
 * patches, GPLv3 license text, and sample scripts/datasets from Android assets
 * into the app's private filesDir on first run.
 */
object PsppAssetExtractor {

    data class ExtractedEnvironment(
        val dataDir: File,
        val tmpDir: File,
        val localeDir: File,
        val samplesDir: File,
        val patchesDir: File,
        val expectedDir: File,
        val defaultClinicalDataset: PsppDataset
    )

    fun ensureExtracted(context: Context): ExtractedEnvironment {
        val psppRoot = File(context.filesDir, "pspp").apply { mkdirs() }
        val tmpDir = File(context.cacheDir, "pspp_tmp").apply { mkdirs() }
        val localeDir = File(psppRoot, "locale").apply { mkdirs() }
        val samplesDir = File(psppRoot, "samples").apply { mkdirs() }
        val patchesDir = File(psppRoot, "patches").apply { mkdirs() }
        val expectedDir = File(psppRoot, "expected").apply { mkdirs() }

        copyAssetFile(context, "pspp/COPYING", File(psppRoot, "COPYING"))

        val sampleFiles = listOf(
            "clinical_trial.csv",
            "descriptives.sps",
            "crosstabs.sps",
            "regression.sps"
        )
        sampleFiles.forEach { name ->
            copyAssetFile(context, "pspp/samples/$name", File(samplesDir, name))
        }

        val expectedFiles = listOf(
            "descriptives_expected.txt",
            "regression_expected.txt"
        )
        expectedFiles.forEach { name ->
            copyAssetFile(context, "pspp/expected/$name", File(expectedDir, name))
        }

        val patchFiles = listOf(
            "01-disable-cairo-pango.patch",
            "02-android-gnulib-bionic.patch",
            "03-android-tempdir-and-relocatable-paths.patch",
            "04-library-reentrant-reset-and-exit-guard.patch"
        )
        patchFiles.forEach { name ->
            copyAssetFile(context, "pspp/patches/$name", File(patchesDir, name))
        }

        val locales = listOf("en", "ar", "fr")
        locales.forEach { lang ->
            val langDir = File(localeDir, "$lang/LC_MESSAGES").apply { mkdirs() }
            // Remove any legacy .po file if present and extract compiled binary .mo catalog
            File(langDir, "pspp.po").delete()
            copyAssetFile(context, "pspp/locale/$lang/LC_MESSAGES/pspp.mo", File(langDir, "pspp.mo"))
        }

        // Load clinical_trial.csv, enrich with rich variable & value labels, and also write clinical_trial.sav
        val csvFile = File(samplesDir, "clinical_trial.csv")
        val rawCsv = if (csvFile.exists()) csvFile.readText() else ""
        val parsed = SavBinaryCodec.parseCsvToDataset("clinical_trial", rawCsv)

        val enrichedVars = parsed.variables.map { v ->
            when (v.name) {
                "id" -> v.copy(label = "Patient ID", decimals = 0, measure = MeasureLevel.NOMINAL)
                "group" -> v.copy(
                    label = "Treatment Arm",
                    decimals = 0,
                    measure = MeasureLevel.NOMINAL,
                    valueLabels = mapOf(
                        "1" to "Intensive Therapy",
                        "2" to "Standard Care",
                        "3" to "Placebo Control"
                    )
                )
                "gender" -> v.copy(
                    label = "Patient Gender",
                    decimals = 0,
                    measure = MeasureLevel.NOMINAL,
                    valueLabels = mapOf("1" to "Male", "2" to "Female")
                )
                "age" -> v.copy(label = "Age in Years", decimals = 0, measure = MeasureLevel.SCALE)
                "baseline_bp" -> v.copy(label = "Baseline Systolic BP (mmHg)", decimals = 1, measure = MeasureLevel.SCALE)
                "followup_bp" -> v.copy(label = "Follow-up Systolic BP (mmHg)", decimals = 1, measure = MeasureLevel.SCALE)
                "cholesterol" -> v.copy(label = "Total Cholesterol (mg/dL)", decimals = 1, measure = MeasureLevel.SCALE)
                "bmi" -> v.copy(label = "Body Mass Index (kg/m2)", decimals = 1, measure = MeasureLevel.SCALE)
                "adherence_score" -> v.copy(label = "Medication Adherence (%)", decimals = 1, measure = MeasureLevel.SCALE)
                "qol_item1" -> v.copy(label = "QoL Physical Functioning", decimals = 0, measure = MeasureLevel.ORDINAL)
                "qol_item2" -> v.copy(label = "QoL Emotional Well-Being", decimals = 0, measure = MeasureLevel.ORDINAL)
                "qol_item3" -> v.copy(label = "QoL Social Vitality", decimals = 0, measure = MeasureLevel.ORDINAL)
                "qol_item4" -> v.copy(label = "QoL Sleep Quality", decimals = 0, measure = MeasureLevel.ORDINAL)
                "outcome_score" -> v.copy(label = "Composite Clinical Outcome Score", decimals = 1, measure = MeasureLevel.SCALE)
                else -> v
            }
        }

        val enrichedDataset = parsed.copy(
            name = "clinical_trial",
            sourcePath = File(samplesDir, "clinical_trial.sav").absolutePath,
            variables = enrichedVars
        )

        val savFile = File(samplesDir, "clinical_trial.sav")
        if (!savFile.exists()) {
            SavBinaryCodec.saveDatasetToSavFile(enrichedDataset, savFile)
        }

        return ExtractedEnvironment(
            dataDir = psppRoot,
            tmpDir = tmpDir,
            localeDir = localeDir,
            samplesDir = samplesDir,
            patchesDir = patchesDir,
            expectedDir = expectedDir,
            defaultClinicalDataset = enrichedDataset
        )
    }

    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        try {
            destFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            // Ignore if optional asset is absent during unit test mocks
        }
    }
}
