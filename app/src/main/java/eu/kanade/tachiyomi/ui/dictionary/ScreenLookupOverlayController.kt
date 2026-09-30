package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import chimahon.DictionaryRepository
import chimahon.MediaInfo
import chimahon.ocr.CropPresets
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.data.ocr.OcrEngineType
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.ui.reader.viewer.orderedDisplayText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val OcrEngineType.preferenceKey: String
    get() = when (this) {
        OcrEngineType.CLOUD -> "cloud"
        OcrEngineType.LOCAL -> "local"
        OcrEngineType.PADDLE -> "paddle"
    }

private val OcrEngineType.label: String
    get() = when (this) {
        OcrEngineType.CLOUD -> "Cloud"
        OcrEngineType.LOCAL -> "Local"
        OcrEngineType.PADDLE -> "Paddle OCR"
    }

/** Clamp bounds for the draggable control bar, in pixels. */
private data class BarBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    fun clamp(offset: Offset): Offset = Offset(
        x = offset.x.coerceIn(minX, maxX),
        y = offset.y.coerceIn(minY, maxY),
    )
}

@Composable
private fun rememberBarBounds(widthPx: Float, heightPx: Float): BarBounds {
    val density = LocalDensity.current
    return remember(widthPx, heightPx, density) {
        with(density) {
            BarBounds(
                minX = -(widthPx - 90.dp.toPx()),
                maxX = 12.dp.toPx(),
                minY = -16.dp.toPx(),
                maxY = heightPx - 80.dp.toPx(),
            )
        }
    }
}

internal class ScreenLookupOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit,
) {
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var screenshot: Bitmap? = null
    private var cachedWebView: WebView? = null
    private var cachedProfile: chimahon.anki.AnkiProfile? = null
    private var overlayBackHandler: (() -> Boolean)? = null
    private var overlayBackCallback: Any? = null
    private val lookupWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lookupWarmupJob: Job? = null
    private var lastBarOffset: Offset = Offset.Zero

    val isShowing: Boolean
        get() = overlayView != null

    fun show(nextScreenshot: Bitmap) {
        dismiss(recycleScreenshot = true, notify = false)
        screenshot = nextScreenshot

        val profile = cachedProfile
            ?: Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile()
                .also { cachedProfile = it }
        val webView = cachedWebView
            ?: prepareDictionaryWebViewShell(context, languageCode = profile.languageCode)
                .also { cachedWebView = it }
        lookupWarmupJob?.cancel()
        lookupWarmupJob = lookupWarmupScope.launch {
            Injekt.get<DictionaryRepository>().warmUp(getDictionaryPaths(context, profile), profile.id)
        }

        val owner = OverlayLifecycleOwner().also {
            it.performCreate()
            it.performStart()
            it.performResume()
        }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        handleBack()
                    }
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setComposeContent {
                ScreenLookupOverlay(
                    screenshot = nextScreenshot,
                    webView = webView,
                    activeProfile = profile,
                    initialBarOffset = lastBarOffset,
                    onBarOffsetChanged = { lastBarOffset = it },
                    onClose = { dismiss() },
                    onBack = { overlayBackHandler = it },
                )
            }
        }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            } else {
                @Suppress("DEPRECATION")
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(view, params)
        overlayView = view
        view.requestFocus()
        registerBackCallback(view)
    }

    fun dismiss(recycleScreenshot: Boolean = true, notify: Boolean = true) {
        overlayView?.let { view ->
            unregisterBackCallback(view)
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        overlayBackHandler = null
        lifecycleOwner?.performDestroy()
        lifecycleOwner = null
        if (recycleScreenshot) {
            screenshot?.takeUnless { it.isRecycled }?.recycle()
        }
        screenshot = null
        if (notify) onDismiss()
    }

    fun release() {
        dismiss(recycleScreenshot = true, notify = false)
        lookupWarmupJob?.cancel()
        lookupWarmupJob = null
        cachedWebView?.runCatching { destroy() }
        cachedWebView = null
        cachedProfile = null
        lastBarOffset = Offset.Zero
    }

    private fun handleBack() {
        if (overlayBackHandler?.invoke() != true) {
            dismiss()
        }
    }

    private fun registerBackCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { handleBack() }
        overlayBackCallback = callback
        view.findOnBackInvokedDispatcher()
            ?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    private fun unregisterBackCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        (overlayBackCallback as? OnBackInvokedCallback)?.let { callback ->
            view.findOnBackInvokedDispatcher()
                ?.unregisterOnBackInvokedCallback(callback)
        }
        overlayBackCallback = null
    }
}

private class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = store

    fun performCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun performStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun performResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun performDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

@Composable
internal fun ScreenLookupOverlay(
    screenshot: Bitmap,
    webView: WebView,
    activeProfile: chimahon.anki.AnkiProfile,
    initialBarOffset: Offset = Offset.Zero,
    onBarOffsetChanged: (Offset) -> Unit = {},
    onClose: () -> Unit,
    onBack: ((() -> Boolean) -> Unit)? = null,
    type: String = "screen",
    mediaInfo: MediaInfo? = null,
    titleId: String? = null,
    onRequestSentenceAudio: (suspend () -> ByteArray?)? = null,
) {
    val context = LocalContext.current
    val localDensity = LocalDensity.current
    val repository = remember { Injekt.get<DictionaryRepository>() }
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val boxScaleX = dictionaryPreferences.ocrBoxScaleX().get()
    val boxScaleY = dictionaryPreferences.ocrBoxScaleY().get()

    val ocrEnginePref = remember { dictionaryPreferences.ocrEngine() }
    val ocrEngineKey by ocrEnginePref.collectAsState()
    val ocrEngine = OcrEngineType.fromPreference(ocrEngineKey)
    var barOffset by remember { mutableStateOf(initialBarOffset) }
    var isDropdownOpen by remember { mutableStateOf(false) }

    var matchedCharCount by remember { mutableIntStateOf(0) }
    var matchOffset by remember { mutableIntStateOf(0) }
    var blocks by remember { mutableStateOf<List<OcrTextBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<OcrSelection?>(null) }
    var lookupNonce by remember { mutableIntStateOf(0) }

    SideEffect {
        onBack?.invoke {
            if (isDropdownOpen) {
                isDropdownOpen = false
            } else if (selection != null) {
                selection = null
            } else {
                onClose()
            }
            true
        }
    }

    LaunchedEffect(screenshot, activeProfile.languageCode, ocrEngine) {
        isLoading = true
        error = null
        blocks = emptyList()
        val language = OcrLanguage.entries.find {
            it.bcp47.equals(activeProfile.languageCode, ignoreCase = true)
        } ?: OcrLanguage.JAPANESE

        runCatching {
            withContext(Dispatchers.Default) {
                recognizePage(screenshot, language, ocrEngine)
                    .toScreenLookupBlocks(language.bcp47)
            }
        }.onSuccess {
            blocks = it
            if (it.isEmpty()) {
                error = context.contextStringResource(MR.strings.screen_lookup_no_text)
            }
        }.onFailure {
            error = it.message ?: context.contextStringResource(MR.strings.screen_lookup_capture_failed)
        }
        isLoading = false
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val widthPx = with(localDensity) { maxWidth.toPx() }
        val heightPx = with(localDensity) { maxHeight.toPx() }
        val barBounds = rememberBarBounds(widthPx, heightPx)

        LaunchedEffect(barBounds) {
            val clamped = barBounds.clamp(barOffset)
            if (clamped != barOffset) {
                barOffset = clamped
                onBarOffsetChanged(clamped)
            }
        }

        if (isDropdownOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(99f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isDropdownOpen = false },
                    ),
            )
        }

        ScreenLookupControls(
            ocrEngine = ocrEngine,
            isDropdownOpen = isDropdownOpen,
            onToggleDropdown = { isDropdownOpen = !isDropdownOpen },
            onSelectOcrEngine = { selected ->
                isDropdownOpen = false
                if (selected != ocrEngine) {
                    ocrEnginePref.set(selected.preferenceKey)
                    when (selected) {
                        OcrEngineType.LOCAL -> Injekt.get<ModelDownloader>().triggerDownload()
                        OcrEngineType.PADDLE -> Injekt.get<ModelDownloader>().triggerPaddleDownload()
                        OcrEngineType.CLOUD -> Unit
                    }
                    blocks = emptyList()
                }
            },
            onCopy = {
                val allText = blocks
                    .sortedWith(compareBy({ it.ymin }, { it.xmin }))
                    .map { it.orderedDisplayText.ifBlank { it.orderedFullText }.ifBlank { it.fullText } }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                if (allText.isNotBlank()) {
                    context.copyToClipboard("OCR", allText)
                } else {
                    context.toast(MR.strings.screen_lookup_no_text)
                }
            },
            onDrag = { dragAmount ->
                val updated = barBounds.clamp(
                    Offset(barOffset.x + dragAmount.x, barOffset.y + dragAmount.y),
                )
                barOffset = updated
                onBarOffsetChanged(updated)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 12.dp)
                .offset { IntOffset(barOffset.x.roundToInt(), barOffset.y.roundToInt()) }
                .zIndex(100f),
        )

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
            onEmptyTap = {
                selection = null
            },
        )

        OcrStatusOverlay(
            isLoading = isLoading,
            error = error,
            loadingText = stringResource(MR.strings.screen_lookup_finding_text),
            modifier = Modifier.align(Alignment.Center),
        )

        val selected = selection
        val cropMode = activeProfile.ankiCropMode
        val cropPresetKey = activeProfile.ankiCropPreset
        val cropPreset = CropPresets.aspectByKey(cropPresetKey)

        // Compute the crop only when the selection or crop settings actually change,
        // not on every barOffset / highlight recomposition.
        val popupScreenshot: Bitmap? = remember(
            screenshot,
            selected?.block,
            selected?.sentenceOffset,
            cropMode,
            cropPresetKey,
        ) {
            when {
                selected == null -> null
                cropMode == "no_screenshot" -> null
                cropPreset != null -> cropAroundAnchor(
                    bitmap = screenshot,
                    anchorX = selected.anchorX,
                    anchorY = selected.anchorY,
                    anchorWidth = selected.anchorWidth,
                    anchorHeight = selected.anchorHeight,
                    aspectX = cropPreset.x,
                    aspectY = cropPreset.y,
                )
                else -> screenshot
            }
        }
        val popupOnRequestScreenshot: (() -> Bitmap?)? = when {
            cropMode == "no_screenshot" -> null
            popupScreenshot != null -> ({ popupScreenshot })
            else -> null
        }

        if (selected != null) {
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
                    type = type,
                    mediaInfo = mediaInfo,
                    screenshot = popupScreenshot,
                    onRequestScreenshot = popupOnRequestScreenshot,
                    onRequestSentenceAudio = onRequestSentenceAudio,
                    usePopup = false,
                    titleId = titleId,
                    onTermMatched = { count, off ->
                        matchedCharCount = count
                        matchOffset = off
                    },
                )
            }
        }
    }
}

@Composable
private fun ScreenLookupControls(
    ocrEngine: OcrEngineType,
    isDropdownOpen: Boolean,
    onToggleDropdown: () -> Unit,
    onSelectOcrEngine: (OcrEngineType) -> Unit,
    onCopy: () -> Unit,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.55f),
            shadowElevation = 4.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onToggleDropdown,
                        modifier = Modifier.size(40.dp),
                    ) {
                        OcrEngineIcon(
                            engine = ocrEngine,
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy text",
                            tint = Color.White,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(14.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp)),
                    )
                }
            }
        }

        if (isDropdownOpen) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1E1E).copy(alpha = 0.96f),
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            ) {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .padding(vertical = 4.dp),
                ) {
                    OcrEngineType.entries.forEach { option ->
                        DropdownEngineItem(
                            option = option,
                            isSelected = option == ocrEngine,
                            onClick = { onSelectOcrEngine(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownEngineItem(
    option: OcrEngineType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val contentColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
        OcrEngineIcon(
            engine = option,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = option.label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun OcrEngineIcon(
    engine: OcrEngineType,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    when (engine) {
        OcrEngineType.LOCAL -> Icon(
            imageVector = Icons.Outlined.Smartphone,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
        OcrEngineType.PADDLE -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = tint,
            )
            Text(
                text = "2",
                color = tint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.offset(y = (-0.5).dp),
            )
        }
        OcrEngineType.CLOUD -> Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}
