package eu.kanade.tachiyomi.ui.player.controls

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import chimahon.DictionaryRepository
import chimahon.MediaInfo
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.OcrBlockCanvas
import eu.kanade.tachiyomi.ui.dictionary.OcrSelection
import eu.kanade.tachiyomi.ui.dictionary.OcrStatusOverlay
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.dictionary.resolveOcrTap
import eu.kanade.tachiyomi.ui.dictionary.toScreenLookupBlocks
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.mapToFitViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

@Composable
internal fun PlayerVideoOcrOverlay(
    viewModel: PlayerViewModel,
    screenshot: Bitmap?,
    onDismiss: () -> Unit,
) {
    if (screenshot == null) return

    val context = LocalContext.current
    val localDensity = LocalDensity.current
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val repository = remember { Injekt.get<DictionaryRepository>() }
    val source by viewModel.currentSource.collectAsState()
    val anime by viewModel.currentAnime.collectAsState()
    val episode by viewModel.currentEpisode.collectAsState()
    val activeProfile = remember(source?.id, anime?.id, source?.lang) {
        dictionaryPreferences.profileResolver.resolve(
            animeId = anime?.id ?: 0L,
            sourceId = source?.id ?: 0L,
            sourceLang = source?.lang.orEmpty(),
        )
    }
    val webView: WebView = remember(activeProfile.languageCode) {
        DictionaryPopupWebViewWarmup.acquire(context, activeProfile.languageCode)
    }
    val boxScaleX = dictionaryPreferences.ocrBoxScaleX().get()
    val boxScaleY = dictionaryPreferences.ocrBoxScaleY().get()

    LaunchedEffect(activeProfile) {
        withContext(Dispatchers.IO) {
            repository.warmUp(getDictionaryPaths(context, activeProfile), activeProfile.id)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            DictionaryPopupWebViewWarmup.recycle(context, webView)
        }
    }

    BackHandler(onBack = onDismiss)

    var matchedCharCount by remember { mutableIntStateOf(0) }
    var matchOffset by remember { mutableIntStateOf(0) }
    var blocks by remember { mutableStateOf<List<OcrTextBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<OcrSelection?>(null) }
    var lookupNonce by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val widthPx = with(localDensity) { maxWidth.toPx() }
        val heightPx = with(localDensity) { maxHeight.toPx() }

        LaunchedEffect(screenshot, activeProfile.languageCode) {
            isLoading = true
            error = null
            blocks = emptyList()
            val language = OcrLanguage.entries.find {
                it.bcp47.equals(activeProfile.languageCode, ignoreCase = true)
            } ?: OcrLanguage.JAPANESE

            runCatching {
                withContext(Dispatchers.Default) {
                    recognizePage(screenshot, language)
                        .toScreenLookupBlocks(language.bcp47)
                }
            }.onSuccess { ocrBlocks ->
                blocks = mapToFitViewport(
                    blocks = ocrBlocks,
                    imgWidth = screenshot.width,
                    imgHeight = screenshot.height,
                    canvasWidth = widthPx,
                    canvasHeight = heightPx,
                )
                if (blocks.isEmpty()) {
                    error = context.contextStringResource(MR.strings.screen_lookup_no_text)
                }
            }.onFailure {
                error = it.message ?: context.contextStringResource(MR.strings.screen_lookup_capture_failed)
            }
            isLoading = false
        }

        OcrBlockCanvas(
            blocks = blocks,
            boxScaleX = boxScaleX,
            boxScaleY = boxScaleY,
            activeBlock = selection?.block,
            activeMatchCount = matchedCharCount,
            activeMatchOffset = matchOffset,
            selection = selection,
            onBlockTapped = { tapped, tapX, tapY, lineIndex ->
                val next = resolveOcrTap(
                    tapped, tapX, tapY, lineIndex,
                    canvasWidth = widthPx, canvasHeight = heightPx,
                    currentSelection = selection,
                )
                if (next != null) {
                    lookupNonce++
                    selection = next
                } else {
                    selection = null
                }
                matchedCharCount = 0
                matchOffset = 0
            },
            onEmptyTap = { onDismiss() },
        )

        OcrStatusOverlay(
            isLoading = isLoading,
            error = error,
            loadingText = stringResource(MR.strings.screen_lookup_finding_text),
            modifier = Modifier.align(Alignment.Center),
        )

        val selected = selection
        if (selected != null) {
            val mediaRequest = remember(selected, lookupNonce) {
                viewModel.createVideoOcrAudioMediaRequest()
            }
            key(selected.lookupString, lookupNonce) {
                OcrLookupPopup(
                    visible = true,
                    lookupString = selected.lookupString,
                    fullText = selected.sentence,
                    charOffset = selected.sentenceOffset,
                    onDismiss = { selection = null },
                    webView = webView,
                    repository = repository,
                    anchorX = selected.anchorX,
                    anchorY = selected.anchorY,
                    anchorWidth = selected.anchorWidth,
                    anchorHeight = selected.anchorHeight,
                    isVertical = selected.block.vertical,
                    activeProfile = activeProfile,
                    type = "anime",
                    mediaInfo = MediaInfo(
                        mangaTitle = anime?.title.orEmpty(),
                        chapterName = episode?.name.orEmpty(),
                    ),
                    onRequestAnimatedScene = { viewModel.captureVideoOcrAnimatedForAnki() },
                    mediaRequest = mediaRequest,
                    onAnkiMediaWarnings = context::showPlayerAnkiMediaWarnings,
                    usePopup = false,
                    titleId = anime?.id?.toString(),
                    onTermMatched = { count, off ->
                        matchedCharCount = count
                        matchOffset = off
                    },
                )
            }
        }
    }
}
