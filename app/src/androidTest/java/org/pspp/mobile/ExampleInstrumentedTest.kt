package org.pspp.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.io.SavBinaryCodec
import org.pspp.mobile.model.AppLanguage
import java.io.File

/**
 * Instrumented CI test executed on the Android Emulator in GitHub Actions
 * (reactivecircus/android-emulator-runner) with `-PallowFallback=false`.
 *
 * Verifies:
 *   1. `org.pspp.mobile` applicationId and real `libpspp_jni.so` native engine loading (`usedNativeJni == true`).
 *   2. Running `descriptives.sps` and `regression.sps` through the real native GNU PSPP 2.1.2 engine
 *      and comparing against stored expected output files (`descriptives_expected.txt`, `regression_expected.txt`).
 *   3. `.sav` round-trip test: writing a `.sav` file with the app codec ([SavBinaryCodec.writeSavBytes])
 *      and reading/executing it back through the real native PSPP engine (`PsppBridge.readDatasetFile`
 *      and `GET FILE='...'` via `PsppBridge.runSyntax`).
 *   4. Compiled `.mo` gettext catalogs extracted under `filesDir/pspp/locale/<lang>/LC_MESSAGES/pspp.mo`.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun verifyNativeEngineDescriptivesAndRegressionExpectedOutput() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.pspp.mobile", appContext.packageName)

        val env = PsppBridge.init(appContext)
        assertTrue("Real native engine libpspp_jni.so must be loaded in CI", PsppBridge.isNativeLoaded)

        // Verify compiled .mo files exist in extracted localeDir
        for (lang in listOf("en", "ar", "fr")) {
            val moFile = File(env.localeDir, "$lang/LC_MESSAGES/pspp.mo")
            assertTrue("Missing compiled .mo catalog for $lang", moFile.exists() && moFile.length() > 16)
        }

        // 1. Run descriptives.sps through the real native engine and compare against descriptives_expected.txt
        val descScript = File(env.samplesDir, "descriptives.sps").readText()
        val descExpected = File(env.expectedDir, "descriptives_expected.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val (descResult, _) = PsppBridge.runSyntax(
            context = appContext,
            scriptContent = descScript,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )
        assertEquals("descriptives.sps exit code must be 0", 0, descResult.exitCode)
        assertTrue("descriptives.sps must execute on real native JNI engine", descResult.usedNativeJni)
        assertFalse(
            "Native engine output must NOT contain approximation banner",
            descResult.plainTextContent.contains(PsppBridge.APPROXIMATION_BANNER)
        )
        for (token in descExpected) {
            assertTrue(
                "Native descriptives.sps output missing expected token '$token'",
                descResult.plainTextContent.contains(token) || descResult.htmlContent.contains(token)
            )
        }

        // 2. Run regression.sps through the real native engine and compare against regression_expected.txt
        val regScript = File(env.samplesDir, "regression.sps").readText()
        val regExpected = File(env.expectedDir, "regression_expected.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val (regResult, _) = PsppBridge.runSyntax(
            context = appContext,
            scriptContent = regScript,
            activeDataset = env.defaultClinicalDataset,
            format = "html",
            language = AppLanguage.EN
        )
        assertEquals("regression.sps exit code must be 0", 0, regResult.exitCode)
        assertTrue("regression.sps must execute on real native JNI engine", regResult.usedNativeJni)
        for (token in regExpected) {
            assertTrue(
                "Native regression.sps output missing expected token '$token'",
                regResult.plainTextContent.contains(token) || regResult.htmlContent.contains(token)
            )
        }
    }

    @Test
    fun verifySavCodecToRealNativeEngineRoundTrip() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val env = PsppBridge.init(appContext)
        assertTrue("Real native engine libpspp_jni.so must be loaded in CI", PsppBridge.isNativeLoaded)

        // Write .sav binary using the app's SavBinaryCodec
        val original = env.defaultClinicalDataset
        val writtenSavFile = File(env.tmpDir, "ci_roundtrip_test.sav")
        val savBytes = SavBinaryCodec.writeSavBytes(original)
        writtenSavFile.writeBytes(savBytes)
        assertTrue(writtenSavFile.exists() && writtenSavFile.length() > 100)

        // Read back with the real native engine via PsppBridge.readDatasetFile (which calls nativeReadDataset)
        val readBackDataset = PsppBridge.readDatasetFile(writtenSavFile)
        assertEquals(20, readBackDataset.caseCount)
        assertEquals(14, readBackDataset.variableCount)
        assertEquals("142", readBackDataset.rows[0][4].removeSuffix(".0"))

        // Also run a PSPP syntax script on the real native engine reading the app-written .sav file directly
        val verifySavScript = """
            GET FILE='${writtenSavFile.absolutePath}'.
            DESCRIPTIVES VARIABLES=age baseline_bp followup_bp outcome_score
              /STATISTICS=MEAN STDDEV MIN MAX.
            EXECUTE.
        """.trimIndent()

        val (savEngineResult, _) = PsppBridge.runSyntax(
            context = appContext,
            scriptContent = verifySavScript,
            activeDataset = readBackDataset,
            format = "html",
            language = AppLanguage.EN
        )
        assertEquals("GET FILE round-trip on real engine must succeed", 0, savEngineResult.exitCode)
        assertTrue("GET FILE round-trip must use real native JNI", savEngineResult.usedNativeJni)
        assertTrue(savEngineResult.plainTextContent.contains("50.05") || savEngineResult.htmlContent.contains("50.05"))
        assertTrue(savEngineResult.plainTextContent.contains("145.70") || savEngineResult.htmlContent.contains("145.70"))
        assertTrue(savEngineResult.plainTextContent.contains("136.40") || savEngineResult.htmlContent.contains("136.40"))
        assertTrue(savEngineResult.plainTextContent.contains("69.35") || savEngineResult.htmlContent.contains("69.35"))
    }
}
