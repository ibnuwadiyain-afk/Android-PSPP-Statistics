package org.pspp.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import org.pspp.mobile.R
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.model.PsppRunResult
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.Emerald400
import org.pspp.mobile.ui.theme.JetBrainsMonoFontFamily
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputViewerScreen(
    isNativeJniLoaded: Boolean = false,
    result: PsppRunResult?,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    onExportSaf: (format: String, suggestedName: String) -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("output_viewer_screen"),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = activeTab == 0,
                            onClick = { onSelectTab(0) },
                            label = { Text(stringResource(R.string.output_html_tab)) },
                            leadingIcon = { Icon(Icons.Default.Html, contentDescription = null) },
                            modifier = Modifier.testTag("output_tab_html")
                        )
                        FilterChip(
                            selected = activeTab == 1,
                            onClick = { onSelectTab(1) },
                            label = { Text(stringResource(R.string.output_text_tab)) },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            modifier = Modifier.testTag("output_tab_text")
                        )
                        FilterChip(
                            selected = activeTab == 2,
                            onClick = { onSelectTab(2) },
                            label = { Text(stringResource(R.string.output_csv_tab)) },
                            leadingIcon = { Icon(Icons.Default.TableView, contentDescription = null) },
                            modifier = Modifier.testTag("output_tab_csv")
                        )
                    }

                    if (result != null) {
                        Text(
                            text = "${result.durationMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = Emerald400
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { onExportSaf("html", "pspp_output.html") },
                        label = { Text(stringResource(R.string.export_html)) },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                        modifier = Modifier.testTag("export_html_button")
                    )
                    AssistChip(
                        onClick = {
                            webViewRef?.let { wv -> printWebViewToPdf(context, wv) }
                        },
                        label = { Text(stringResource(R.string.export_pdf)) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                        modifier = Modifier.testTag("export_pdf_button")
                    )
                    AssistChip(
                        onClick = { onExportSaf("txt", "pspp_output.txt") },
                        label = { Text(stringResource(R.string.export_txt)) },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        modifier = Modifier.testTag("export_txt_button")
                    )
                    AssistChip(
                        onClick = { onExportSaf("csv_out", "pspp_output.csv") },
                        label = { Text("CSV") },
                        leadingIcon = { Icon(Icons.Default.TableView, contentDescription = null) },
                        modifier = Modifier.testTag("export_output_csv_button")
                    )
                    AssistChip(
                        onClick = {
                            if (result != null) {
                                shareOutputViaIntent(context, result, activeTab)
                            }
                        },
                        label = { Text(stringResource(R.string.share_output)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        modifier = Modifier.testTag("share_output_button")
                    )
                }
            }
        }

        if (result == null) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No PSPP output yet — run a syntax script or statistical procedure.",
                        style = MaterialTheme.typography.titleMedium,
                        color = Cyan400,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    webViewClient = WebViewClient()
                                    webViewRef = this
                                }
                            },
                            update = { wv ->
                                webViewRef = wv
                                wv.loadDataWithBaseURL(
                                    "file:///android_asset/",
                                    result.htmlContent,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("output_webview")
                        )
                    }
                    1 -> {
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        Text(
                            text = result.plainTextContent,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                                .padding(12.dp)
                                .testTag("output_plain_text")
                        )
                    }
                    else -> {
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        Text(
                            text = result.csvContent,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                                .padding(12.dp)
                                .testTag("output_csv_text")
                        )
                    }
                }
            }
        }
    }
}

private fun printWebViewToPdf(context: Context, webView: WebView) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = "GNU_PSPP_2_1_2_Output_${System.currentTimeMillis()}"
    val printAdapter = webView.createPrintDocumentAdapter(jobName)
    printManager.print(
        jobName,
        printAdapter,
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build()
    )
}

private fun shareOutputViaIntent(context: Context, result: PsppRunResult, activeTab: Int) {
    try {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val (ext, mime, content) = when (activeTab) {
            1 -> Triple("txt", "text/plain", result.plainTextContent)
            2 -> Triple("csv", "text/csv", result.csvContent)
            else -> Triple("html", "text/html", result.htmlContent)
        }
        val outFile = File(exportDir, "pspp_output.$ext")
        outFile.writeText(content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, "GNU PSPP 2.1.2 Statistical Output")
            putExtra(Intent.EXTRA_TEXT, result.plainTextContent.take(2000))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share PSPP Output"))
    } catch (_: Exception) {
        // Fallback plain text share if FileProvider is unavailable in unit test context
        val fallback = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, result.plainTextContent)
        }
        context.startActivity(Intent.createChooser(fallback, "Share PSPP Output"))
    }
}
