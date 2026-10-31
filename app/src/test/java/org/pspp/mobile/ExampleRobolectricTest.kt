package org.pspp.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pspp.mobile.engine.PsppAssetExtractor
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.engine.PsppStatisticalEvaluator
import org.pspp.mobile.engine.PsppSyntaxGenerator
import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.AnalysisProcedure
import org.pspp.mobile.model.AppLanguage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun verifyPackageNameCompiledMoCatalogsAndAssetExtraction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("org.pspp.mobile", context.packageName)
        assertEquals("PSPP Studio", context.getString(R.string.app_name))

        val env = PsppAssetExtractor.ensureExtracted(context)
        assertTrue(File(env.dataDir, "COPYING").exists())
        assertTrue(File(env.samplesDir, "clinical_trial.csv").exists())
        assertTrue(File(env.samplesDir, "clinical_trial.sav").exists())
        assertTrue(File(env.samplesDir, "descriptives.sps").exists())
        assertTrue(File(env.samplesDir, "crosstabs.sps").exists())
        assertTrue(File(env.samplesDir, "regression.sps").exists())
        assertTrue(File(env.expectedDir, "descriptives_expected.txt").exists())
        assertTrue(File(env.expectedDir, "regression_expected.txt").exists())

        // Requirement 2: Verify compiled GNU gettext .mo binary catalogs exist for en, ar, fr (magic 0x950412de)
        for (lang in listOf("en", "ar", "fr")) {
            val moFile = File(env.localeDir, "$lang/LC_MESSAGES/pspp.mo")
            val poFile = File(env.localeDir, "$lang/LC_MESSAGES/pspp.po")
            assertTrue("Missing compiled .mo catalog for $lang", moFile.exists() && moFile.length() > 16)
            assertFalse("Legacy .po file should not exist for $lang", poFile.exists())
            val magic = ByteBuffer.wrap(moFile.readBytes(), 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(
                "Invalid .mo binary magic for $lang: 0x${Integer.toHexString(magic)}",
                magic == -0x6afbed22 || magic == -0x21ecfb6b // 0x950412de LE or BE
            )
        }

        assertEquals(20, env.defaultClinicalDataset.caseCount)
        assertEquals(14, env.defaultClinicalDataset.variableCount)
    }

    @Test
    fun verifyFallbackHonestyBannerAndExportStamping() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val expectedStamp = "Approximation mode: results are NOT from the real PSPP engine"

        if (!PsppBridge.isNativeLoaded) {
            assertEquals(expectedStamp, PsppBridge.getEngineVersionBanner())
            val script = File(env.samplesDir, "descriptives.sps").readText()
            val (result, _) = PsppBridge.runSyntax(
                context = context,
                scriptContent = script,
                activeDataset = env.defaultClinicalDataset,
                format = "html",
                language = AppLanguage.EN
            )
            assertTrue("HTML output must contain approximation banner", result.htmlContent.contains(expectedStamp))
            assertTrue("Plain text output must contain approximation banner", result.plainTextContent.contains(expectedStamp))
            assertTrue("CSV output must contain approximation banner", result.csvContent.contains(expectedStamp))
        }
    }

    @Test
    fun verifyBinarySavRoundTripReadWriteAndEngineExecution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val original = env.defaultClinicalDataset

        // Write with the app's SavBinaryCodec
        val roundTripSavFile = File(env.tmpDir, "clinical_trial_roundtrip.sav")
        val savBytes = SavBinaryCodec.writeSavBytes(original)
        assertTrue("SAV header must start with \$FL2", String(savBytes, 0, 4) == "\$FL2")
        roundTripSavFile.writeBytes(savBytes)

        // Read back via PsppBridge.readDatasetFile
        val decoded = PsppBridge.readDatasetFile(roundTripSavFile)
        assertEquals(original.caseCount, decoded.caseCount)
        assertEquals(original.variableCount, decoded.variableCount)
        assertEquals("142.0", decoded.rows[0][4]) // baseline_bp of case 1
        assertEquals("Intensive Therapy", decoded.variables[1].valueLabels["1"])

        // Also run a PSPP syntax script that loads the written .sav via GET FILE and computes descriptives
        val savScript = """
            GET FILE='${roundTripSavFile.absolutePath}'.
            DESCRIPTIVES VARIABLES=age baseline_bp followup_bp outcome_score.
            EXECUTE.
        """.trimIndent()
        val (savRunResult, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = savScript,
            activeDataset = decoded,
            format = "html",
            language = AppLanguage.EN
        )
        assertEquals(0, savRunResult.exitCode)
        assertTrue(savRunResult.plainTextContent.contains("50.05"))
        assertTrue(savRunResult.plainTextContent.contains("145.70"))
        assertTrue(savRunResult.plainTextContent.contains("136.40"))
        assertTrue(savRunResult.plainTextContent.contains("69.35"))
    }

    @Test
    fun verifyBundledDescriptivesSpsAgainstExpectedOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = File(env.samplesDir, "descriptives.sps").readText()
        val expectedTokens = File(env.expectedDir, "descriptives_expected.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

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
        for (token in expectedTokens) {
            assertTrue(
                "descriptives.sps output missing expected token '$token'",
                result.plainTextContent.contains(token) || result.htmlContent.contains(token)
            )
        }
    }

    @Test
    fun verifyBundledRegressionSpsAgainstExpectedOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = PsppBridge.init(context)
        val script = File(env.samplesDir, "regression.sps").readText()
        val expectedTokens = File(env.expectedDir, "regression_expected.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val (result, _) = PsppBridge.runSyntax(
            context = context,
            scriptContent = script,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )

        assertEquals(0, result.exitCode)
        for (token in expectedTokens) {
            assertTrue(
                "regression.sps output missing expected token '$token'",
                result.plainTextContent.contains(token) || result.htmlContent.contains(token)
            )
        }
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
