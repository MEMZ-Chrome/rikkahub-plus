package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.HomePromptConfig
import me.rerere.rikkahub.service.HomePromptItem
import me.rerere.rikkahub.service.HomePromptService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.chat.HomePromptBanner
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingHomePromptPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val homePromptService: HomePromptService = koinInject()
    val scope = rememberCoroutineScope()
    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current

    var config by remember { mutableStateOf(settings.homePromptConfig) }
    var initialized by remember { mutableStateOf(!settings.init) }
    LaunchedEffect(settings.init) {
        if (!settings.init && !initialized) {
            config = settings.homePromptConfig
            initialized = true
        }
    }

    fun save(updated: HomePromptConfig) {
        config = updated
        vm.updateSettings { it.copy(homePromptConfig = updated) }
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<HomePromptItem?>?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_home_prompt_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("switch") {
                CardGroup {
                    switchItem(
                        checked = config.enabled,
                        onCheckedChange = { enabled ->
                            save(config.copy(enabled = enabled))
                        },
                        headlineContent = { Text(stringResource(R.string.setting_home_prompt_enable_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_home_prompt_enable_desc)) },
                    )
                }
            }

            item("script") {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_home_prompt_script_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_home_prompt_script_desc)) },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    save(config.copy(script = HomePromptConfig.DEFAULT_SCRIPT))
                                },
                            ) {
                                Text(stringResource(R.string.setting_home_prompt_insert_example))
                            }
                        },
                    ) {
                        OutlinedTextField(
                            value = config.script,
                            onValueChange = { save(config.copy(script = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 10,
                            maxLines = 22,
                            placeholder = {
                                Text(
                                    text = HomePromptConfig.DEFAULT_SCRIPT,
                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            },
                            visualTransformation = HighlightCodeVisualTransformation(
                                language = "javascript",
                                highlighter = highlighter,
                                darkMode = darkMode,
                            ),
                            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                        )
                    }
                }
            }

            item("test") {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_home_prompt_test_title)) },
                        trailingContent = {
                            FilledTonalButton(
                                onClick = {
                                    if (config.script.isNotBlank() && !testing) {
                                        testing = true
                                        testResult = null
                                        scope.launch {
                                            testResult = homePromptService.fetch(config.script)
                                            testing = false
                                        }
                                    }
                                },
                                enabled = config.script.isNotBlank() && !testing,
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = HugeIcons.Play,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.setting_home_prompt_test_run))
                            }
                        },
                    ) {
                        testResult?.let { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                result.onSuccess { item ->
                                    if (item == null) {
                                        Text(
                                            text = stringResource(R.string.setting_home_prompt_test_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.background,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                HomePromptBanner(
                                                    item = item,
                                                    onClick = {},
                                                )
                                            }
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (item.imageLight.isNotBlank()) {
                                                Text(
                                                    text = if (item.imageDark.isNotBlank()) {
                                                        "image (light): ${item.imageLight}"
                                                    } else {
                                                        "image: ${item.imageLight}"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (item.imageDark.isNotBlank()) {
                                                Text(
                                                    text = "image (dark): ${item.imageDark}",
                                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (item.phrase.isNotBlank()) {
                                                Text(
                                                    text = "phrase: ${item.phrase}",
                                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (item.insertText.isNotBlank()) {
                                                Text(
                                                    text = "prompt: ${item.insertText}",
                                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                                result.onFailure { error ->
                                    Text(
                                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
