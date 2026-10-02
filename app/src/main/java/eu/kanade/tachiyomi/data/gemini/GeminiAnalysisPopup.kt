package eu.kanade.tachiyomi.data.gemini

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.kanade.presentation.manga.components.MarkdownRender
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GeminiAnalysisPopup(
    state: GeminiAnalysisState,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    // Dismiss on system back. Only wired when a dispatcher owner is in
    // the composition — the screen-lookup overlay ComposeView has none,
    // and BackHandler would throw without this guard.
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler { state.dismiss() }
    }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val content = state.content

    val isAtBottom by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max == 0 || scrollState.value >= max - 32
        }
    }

    LaunchedEffect(content) {
        if (content.isEmpty()) {
            scrollState.scrollTo(0)
        } else if (isAtBottom) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(1001f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = state::dismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val panelMaxWidth = minOf(maxWidth - 32.dp, 540.dp)
        val panelMaxHeight = minOf(maxHeight - 32.dp, 560.dp)

        Surface(
            modifier = Modifier
                .widthIn(max = panelMaxWidth)
                .heightIn(min = 160.dp, max = panelMaxHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(scrollState),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.gemini_analysis_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (content.isNotBlank()) {
                        IconButton(onClick = { onCopy(content) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(MR.strings.gemini_action_copy),
                            )
                        }
                    }
                }

                if (state.error != null) {
                    val errorText = when (val err = state.error) {
                        GeminiError.NoApiKey -> stringResource(MR.strings.gemini_error_no_api_key)
                        GeminiError.NoInput -> stringResource(MR.strings.gemini_error_no_input)
                        GeminiError.CustomEndpointBlank -> stringResource(MR.strings.gemini_error_custom_endpoint_blank)
                        GeminiError.CustomEndpointInvalid -> stringResource(MR.strings.gemini_error_custom_endpoint_invalid)
                        is GeminiError.Http -> stringResource(MR.strings.gemini_error_http, err.code, err.message)
                        is GeminiError.Generic -> stringResource(MR.strings.gemini_error_generic, err.message)
                        null -> ""
                    }
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(onClick = { state.retry(scope) }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(MR.strings.gemini_action_retry),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                if (content.isNotEmpty()) {
                    SelectionContainer {
                        if (state.loading) {
                            // While streaming, render plain text. Re-parsing
                            // markdown on every chunk causes the whole body to
                            // briefly blank out and the popup to jitter.
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            MarkdownRender(content = content, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}
