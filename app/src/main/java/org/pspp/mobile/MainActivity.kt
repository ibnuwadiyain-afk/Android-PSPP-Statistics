package org.pspp.mobile

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.pspp.mobile.model.AppLanguage
import org.pspp.mobile.model.AppScreen
import org.pspp.mobile.ui.screens.AboutScreen
import org.pspp.mobile.ui.screens.AnalyzeScreen
import org.pspp.mobile.ui.screens.DataEditorScreen
import org.pspp.mobile.ui.screens.FilesScreen
import org.pspp.mobile.ui.screens.OutputViewerScreen
import org.pspp.mobile.ui.screens.SyntaxEditorScreen
import org.pspp.mobile.ui.theme.Cyan400
import org.pspp.mobile.ui.theme.Emerald400
import org.pspp.mobile.ui.theme.PsppStudioTheme
import org.pspp.mobile.viewmodel.PsppViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PsppStudioTheme {
                PsppWorkbenchRoot()
            }
        }
    }
}

private data class NavDestination(
    val screen: AppScreen,
    val labelRes: Int,
    val icon: ImageVector,
    val tag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsppWorkbenchRoot(
    viewModel: PsppViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current

    // Apply selected language (English, Arabic RTL, French) dynamically to Context & LayoutDirection
    val localizedContext = remember(baseContext, uiState.language) {
        val config = Configuration(baseContext.resources.configuration)
        Locale.setDefault(uiState.language.locale)
        config.setLocale(uiState.language.locale)
        config.setLayoutDirection(uiState.language.locale)
        baseContext.createConfigurationContext(config)
    }

    var pendingSaveFormat by remember { mutableStateOf("sav") }

    // SAF Open Document Launcher (.sav, .zsav, .por, .csv, .txt, .sps)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.handleSafOpenUri(baseContext, uri)
        }
    }

    // SAF Create Document Launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            viewModel.saveToSafUri(baseContext, uri, pendingSaveFormat)
        }
    }

    // BackHandler returns to SYNTAX screen from secondary screens
    if (uiState.currentScreen != AppScreen.SYNTAX) {
        BackHandler {
            viewModel.selectScreen(AppScreen.SYNTAX)
        }
    }

    val destinations = remember {
        listOf(
            NavDestination(AppScreen.SYNTAX, R.string.nav_syntax, Icons.Default.Code, "nav_syntax"),
            NavDestination(AppScreen.DATA, R.string.nav_data, Icons.Default.TableChart, "nav_data"),
            NavDestination(AppScreen.ANALYZE, R.string.nav_analyze, Icons.Default.Analytics, "nav_analyze"),
            NavDestination(AppScreen.OUTPUT, R.string.nav_output, Icons.Default.Web, "nav_output"),
            NavDestination(AppScreen.FILES, R.string.nav_files, Icons.Default.Folder, "nav_files"),
            NavDestination(AppScreen.ABOUT, R.string.nav_about, Icons.Default.Info, "nav_about")
        )
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalLayoutDirection provides uiState.language.layoutDirection
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedScreen = maxWidth >= 700.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${uiState.dataset.name}.sav (${uiState.dataset.caseCount}×${uiState.dataset.variableCount}) • ${uiState.language.displayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Cyan400,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            // One-tap language cycle button (EN -> AR RTL -> FR)
                            IconButton(
                                onClick = {
                                    val nextLang = when (uiState.language) {
                                        AppLanguage.EN -> AppLanguage.AR
                                        AppLanguage.AR -> AppLanguage.FR
                                        AppLanguage.FR -> AppLanguage.EN
                                    }
                                    viewModel.setLanguage(nextLang)
                                },
                                modifier = Modifier.testTag("cycle_language_button")
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = stringResource(R.string.language_label),
                                    tint = Emerald400
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                bottomBar = {
                    if (!isExpandedScreen) {
                        NavigationBar {
                            destinations.forEach { dest ->
                                NavigationBarItem(
                                    selected = uiState.currentScreen == dest.screen,
                                    onClick = { viewModel.selectScreen(dest.screen) },
                                    icon = {
                                        Icon(
                                            dest.icon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag(dest.tag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpandedScreen) {
                        NavigationRail(modifier = Modifier.fillMaxHeight()) {
                            destinations.forEach { dest ->
                                NavigationRailItem(
                                    selected = uiState.currentScreen == dest.screen,
                                    onClick = { viewModel.selectScreen(dest.screen) },
                                    icon = {
                                        Icon(
                                            dest.icon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.tag)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        uiState.statusBannerMessage?.let { msg ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (uiState.currentScreen) {
                                AppScreen.SYNTAX -> SyntaxEditorScreen(
                                    isNativeJniLoaded = uiState.isNativeJniLoaded,
                                    syntaxText = uiState.syntaxText,
                                    selectionStartLine = uiState.selectionStartLine,
                                    selectionEndLine = uiState.selectionEndLine,
                                    diagnostics = uiState.diagnostics,
                                    isRunning = uiState.isRunning,
                                    elapsedRunMs = uiState.elapsedRunMs,
                                    onSyntaxChange = viewModel::updateSyntaxText,
                                    onSelectionRangeChange = viewModel::updateSelectionLines,
                                    onRunAll = viewModel::runAllSyntax,
                                    onRunSelection = viewModel::runSelectedSyntax,
                                    onCancelRun = viewModel::cancelRun,
                                    onLoadSample = viewModel::loadSampleScript,
                                    onOpenSpsSaf = { openDocumentLauncher.launch(arrayOf("*/*")) },
                                    onSaveSpsSaf = {
                                        pendingSaveFormat = "sps"
                                        createDocumentLauncher.launch("analysis.sps")
                                    }
                                )

                                AppScreen.DATA -> DataEditorScreen(
                                    isNativeJniLoaded = uiState.isNativeJniLoaded,
                                    dataset = uiState.dataset,
                                    isVariableView = uiState.isVariableView,
                                    onToggleViewMode = viewModel::setDataEditorMode,
                                    onUpdateCell = viewModel::updateCellValue,
                                    onAddRow = viewModel::addCaseRow,
                                    onDeleteRow = viewModel::deleteCaseRow,
                                    onAddVariable = viewModel::addVariable,
                                    onUpdateVariable = viewModel::updateVariable,
                                    onDeleteVariable = viewModel::deleteVariable,
                                    onSyncToSyntax = viewModel::syncDatasetChangesToSyntax,
                                    onExportSavSaf = {
                                        pendingSaveFormat = "sav"
                                        createDocumentLauncher.launch("${uiState.dataset.name}.sav")
                                    },
                                    onExportCsvSaf = {
                                        pendingSaveFormat = "csv_data"
                                        createDocumentLauncher.launch("${uiState.dataset.name}.csv")
                                    }
                                )

                                AppScreen.ANALYZE -> AnalyzeScreen(
                                    dataset = uiState.dataset,
                                    selectedProcedure = uiState.selectedProcedure,
                                    onSelectProcedure = viewModel::openProcedureDialog,
                                    onRunGeneratedSyntax = { generated ->
                                        viewModel.updateSyntaxText(generated)
                                        viewModel.runSyntaxScript(generated, navigateToOutput = true)
                                    },
                                    onSendSyntaxToEditor = { generated ->
                                        viewModel.updateSyntaxText(generated)
                                        viewModel.selectScreen(AppScreen.SYNTAX)
                                    }
                                )

                                AppScreen.OUTPUT -> OutputViewerScreen(
                                    isNativeJniLoaded = uiState.isNativeJniLoaded,
                                    result = uiState.latestResult,
                                    activeTab = uiState.activeOutputTab,
                                    onSelectTab = viewModel::setOutputTab,
                                    onExportSaf = { fmt, suggestedName ->
                                        pendingSaveFormat = fmt
                                        createDocumentLauncher.launch(suggestedName)
                                    }
                                )

                                AppScreen.FILES -> FilesScreen(
                                    recentFiles = uiState.recentFiles,
                                    csvPreview = uiState.csvPreview,
                                    onOpenSafFile = { openDocumentLauncher.launch(arrayOf("*/*")) },
                                    onSaveSavSaf = {
                                        pendingSaveFormat = "sav"
                                        createDocumentLauncher.launch("${uiState.dataset.name}.sav")
                                    },
                                    onSaveCsvSaf = {
                                        pendingSaveFormat = "csv_data"
                                        createDocumentLauncher.launch("${uiState.dataset.name}.csv")
                                    },
                                    onOpenSampleCsvPreview = viewModel::openSampleCsvInPreview,
                                    onUpdateCsvPreviewOptions = viewModel::updateCsvPreviewOptions,
                                    onApplyCsvImport = viewModel::applyCsvPreviewImport,
                                    onDismissCsvPreview = viewModel::dismissCsvPreview,
                                    onOpenRecentFile = { recent ->
                                        viewModel.openLocalFilePath(recent.uriOrPath)
                                    }
                                )

                                AppScreen.ABOUT -> AboutScreen(
                                    engineBanner = uiState.engineBanner,
                                    isNativeJniLoaded = uiState.isNativeJniLoaded,
                                    currentLanguage = uiState.language,
                                    patchesMap = uiState.patchesMap,
                                    gplLicenseText = uiState.gplLicenseText,
                                    onSelectLanguage = viewModel::setLanguage
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
