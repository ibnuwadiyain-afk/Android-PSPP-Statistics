package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.PsppBridge
import com.example.io.SavBinaryCodec
import com.example.model.AppLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented smoke test executed on the Android Emulator in GitHub Actions CI
 * (reactivecircus/android-emulator-runner).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun smokeTestPsppEngineAndSampleScripts() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.aistudio.psppstudio.gnupsp", appContext.packageName)

        val env = PsppBridge.init(appContext)
        assertTrue(File(env.samplesDir, "clinical_trial.sav").exists())

        for (sampleName in listOf("descriptives.sps", "crosstabs.sps", "regression.sps")) {
            val script = File(env.samplesDir, sampleName).readText()
            val (result, _) = PsppBridge.runSyntax(
                context = appContext,
                scriptContent = script,
                activeDataset = env.defaultClinicalDataset,
                format = "html",
                language = AppLanguage.EN
            )
            assertEquals("Sample $sampleName failed", 0, result.exitCode)
            assertTrue("HTML output empty for $sampleName", result.htmlContent.contains("<table"))
        }

        val savBytes = SavBinaryCodec.writeSavBytes(env.defaultClinicalDataset)
        val roundTrip = SavBinaryCodec.readSavBytes(savBytes, "smoke_test")
        assertEquals(20, roundTrip.caseCount)
    }
}
