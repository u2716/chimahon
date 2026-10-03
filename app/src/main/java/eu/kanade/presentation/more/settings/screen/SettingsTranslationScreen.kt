package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.gemini.GeminiConfig
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Gemini analysis settings, surfaced under the "Learning" section of the
 * top-level settings menu as its own "Translation" entry.
 */
object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> = listOf(getGeminiGroup())

    @Composable
    private fun getGeminiGroup(): Preference.PreferenceGroup {
        val prefs = remember { Injekt.get<DictionaryPreferences>() }
        val geminiModel by prefs.geminiModel().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_gemini),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_gemini_api_key),
                    content = {
                        var isDialogShown by remember { mutableStateOf(false) }
                        val keyPref = prefs.geminiApiKey()
                        val key by keyPref.collectAsState()

                        TextPreferenceWidget(
                            title = stringResource(MR.strings.pref_gemini_api_key),
                            subtitle = if (key.isBlank()) {
                                stringResource(MR.strings.pref_gemini_api_key_summary)
                            } else {
                                stringResource(MR.strings.pref_gemini_api_key_masked)
                            },
                            onPreferenceClick = { isDialogShown = true },
                        )

                        if (isDialogShown) {
                            var text by remember { mutableStateOf(key) }
                            AlertDialog(
                                onDismissRequest = { isDialogShown = false },
                                title = { Text(stringResource(MR.strings.pref_gemini_api_key)) },
                                text = {
                                    OutlinedTextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            keyPref.set(text.trim())
                                            isDialogShown = false
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_ok))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { isDialogShown = false }) {
                                        Text(stringResource(MR.strings.action_cancel))
                                    }
                                },
                            )
                        }
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_gemini_prompt),
                    content = {
                        var isDialogShown by remember { mutableStateOf(false) }
                        val promptPref = prefs.geminiPrompt()
                        val prompt by promptPref.collectAsState()

                        TextPreferenceWidget(
                            title = stringResource(MR.strings.pref_gemini_prompt),
                            subtitle = stringResource(MR.strings.pref_gemini_prompt_summary),
                            onPreferenceClick = { isDialogShown = true },
                        )

                        if (isDialogShown) {
                            var text by remember { mutableStateOf(prompt) }
                            AlertDialog(
                                onDismissRequest = { isDialogShown = false },
                                title = { Text(stringResource(MR.strings.pref_gemini_prompt)) },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 350.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text(stringResource(MR.strings.pref_gemini_prompt_placeholder)) },
                                            minLines = 6,
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            promptPref.set(text)
                                            isDialogShown = false
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_ok))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { isDialogShown = false }) {
                                        Text(stringResource(MR.strings.action_cancel))
                                    }
                                },
                            )
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.geminiSendScreenshot(),
                    title = stringResource(MR.strings.pref_gemini_send_screenshot),
                    subtitle = stringResource(MR.strings.pref_gemini_send_screenshot_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.geminiModel(),
                    title = stringResource(MR.strings.pref_gemini_model),
                    entries = mapOf(
                        GeminiConfig.MODEL_FLASH_LITE_LATEST to stringResource(MR.strings.pref_gemini_model_flash_lite),
                        GeminiConfig.MODEL_FLASH_LATEST to stringResource(MR.strings.pref_gemini_model_flash),
                        GeminiConfig.MODEL_GEMMA_4_26B to stringResource(MR.strings.pref_gemini_model_gemma_4_26b),
                        GeminiConfig.MODEL_GEMMA_4_31B to stringResource(MR.strings.pref_gemini_model_gemma_4_31b),
                        GeminiConfig.MODEL_CUSTOM to stringResource(MR.strings.pref_gemini_model_custom),
                    ).toPersistentMap(),
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_gemini_custom_endpoint),
                    content = {
                        var isDialogShown by remember { mutableStateOf(false) }
                        val endpointPref = prefs.geminiCustomEndpoint()
                        val endpoint by endpointPref.collectAsState()

                        TextPreferenceWidget(
                            title = stringResource(MR.strings.pref_gemini_custom_endpoint),
                            subtitle = stringResource(MR.strings.pref_gemini_custom_endpoint_summary),
                            onPreferenceClick = { isDialogShown = true },
                        )

                        if (isDialogShown) {
                            var text by remember { mutableStateOf(endpoint) }
                            AlertDialog(
                                onDismissRequest = { isDialogShown = false },
                                title = { Text(stringResource(MR.strings.pref_gemini_custom_endpoint)) },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 350.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("https://example.com/v1beta/models/…") },
                                            minLines = 4,
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            endpointPref.set(text.trim())
                                            isDialogShown = false
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_ok))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { isDialogShown = false }) {
                                        Text(stringResource(MR.strings.action_cancel))
                                    }
                                },
                            )
                        }
                    },
                ),
            ),
        )
    }
}
