package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.PsppAssetExtractor
import com.example.engine.PsppBridge
import com.example.engine.PsppStatisticalEvaluator
import com.example.engine.PsppSyntaxGenerator
import com.example.io.SavBinaryCodec
import com.example.model.AnalysisProcedure
import com.example.model.AppLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun verifyAppMetadataAndAssetExtraction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("PSPP Studio", context.getString(R.string.app_name))

        val env = PsppAssetExtractor.ensureExtracted(context)
        assertTrue(File(env.dataDir, "COPYING").exists())
        assertTrue(File(env.samplesDir, "clinical_trial.csv").exists())
        assertTrue(File(env.samplesDir, "clinical_trial.sav").exists())
        assertTrue(File(env.samplesDir, "descriptives.sps").exists())
        assertTrue(File(env.samplesDir, "crosstabs.sps").exists())
        assertTrue(File(env.samplesDir, "regression.sps").exists())
        assertEquals(20, env.defaultClinicalDataset.caseCount)
        assertEquals(14, env.defaultClinicalDataset.variableCount)
    }

    @Test
    fun verifyBinarySavRoundTripReadWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppAssetExtractor.ensureExtracted(context)
        val original = env.defaultClinicalDataset

        val savBytes = SavBinaryCodec.writeSavBytes(original)
        assertTrue("SAV header must start with \$FL2", String(savBytes, 0, 4) == "\$FL2")

        val decoded = SavBinaryCodec.readSavBytes(savBytes, "clinical_trial_roundtrip")
        assertEquals(original.caseCount, decoded.caseCount)
        assertEquals(original.variableCount, decoded.variableCount)
        assertEquals("142.0", decoded.rows[0][4]) // baseline_bp of case 1
        assertEquals("Intensive Therapy", decoded.variables[1].valueLabels["1"])
    }

    @Test
    fun verifyBundledDescriptivesSpsExpectedOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = File(env.samplesDir, "descriptives.sps").readText()

        val (result, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.cancelled)
        assertTrue(result.diagnostics.isEmpty())
        // Expected output comparisons for clinical_trial.csv (N = 20):
        // Mean age = 49.70, Mean baseline_bp = 145.70, Mean followup_bp = 136.40
        assertTrue(result.plainTextContent.contains("Descriptive Statistics"))
        assertTrue(result.plainTextContent.contains("baseline_bp"))
        assertTrue(result.plainTextContent.contains("145.70"))
        assertTrue(result.plainTextContent.contains("136.40"))
        assertTrue(result.plainTextContent.contains("Frequency Table: group"))
        assertTrue(result.htmlContent.contains("<table"))
    }

    @Test
    fun verifyBundledCrosstabsTTestAnovaExpectedOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = File(env.samplesDir, "crosstabs.sps").readText()

        val (result, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.plainTextContent.contains("Crosstabulation: group × gender"))
        assertTrue(result.plainTextContent.contains("Pearson Chi-Square"))
        assertTrue(result.plainTextContent.contains("Independent Samples T-Test"))
        assertTrue(result.plainTextContent.contains("Paired Samples T-Test (baseline_bp - followup_bp)"))
        assertTrue(result.plainTextContent.contains("One-Way ANOVA (Factor: group)"))
    }

    @Test
    fun verifyBundledRegressionCorrelationReliabilityExpectedOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = File(env.samplesDir, "regression.sps").readText()

        val (result, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.plainTextContent.contains("Bivariate Pearson Correlations Matrix"))
        assertTrue(result.plainTextContent.contains("Model Summary (Dependent Variable: outcome_score)"))
        assertTrue(result.plainTextContent.contains("Regression Coefficients (Dependent Variable: outcome_score)"))
        assertTrue(result.plainTextContent.contains("Reliability Statistics (Cronbach's Alpha)"))
    }

    @Test
    fun verifyAllNineProceduresGenerateValidSyntaxAndDiagnostics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppAssetExtractor.ensureExtracted(context)

        for (proc in AnalysisProcedure.entries) {
            val syntax = PsppSyntaxGenerator.generateProcedureSyntax(
                procedure = proc,
                selectedVars = listOf("baseline_bp", "followup_bp", "outcome_score"),
                secondaryVar = "group"
            )
            val diags = PsppStatisticalEvaluator.validateSyntax(syntax, env.defaultClinicalDataset)
            assertTrue("Procedure $proc generated unexpected diagnostics: $diags", diags.isEmpty())
        }

        // Verify broken syntax is flagged with line numbers
        val broken = "UNKNOWN_CMD VARIABLES=age.\nDESCRIPTIVES VARIABLES='unclosed."
        val brokenDiags = PsppStatisticalEvaluator.validateSyntax(broken, env.defaultClinicalDataset)
        assertTrue(brokenDiags.any { it.line == 1 && it.severity == "ERROR" })
        assertTrue(brokenDiags.any { it.line == 2 && it.severity == "ERROR" })
    }

    @Test
    fun verifyArabicRtlAndFrenchLocalizationOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = "DESCRIPTIVES VARIABLES=age baseline_bp.\nEXECUTE."

        val (arResult, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            language = AppLanguage.AR
        )
        assertTrue(arResult.htmlContent.contains("dir=\"rtl\""))
        assertTrue(arResult.htmlContent.contains("الإحصاءات الوصفية"))

        val (frResult, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            language = AppLanguage.FR
        )
        assertTrue(frResult.htmlContent.contains("Statistiques Descriptives"))
    }
}
