package me.rerere.rikkahub.ui.pages.setting

import android.webkit.CookieManager
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tick02
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import me.rerere.search.DeepSeekWebSearchService
import me.rerere.search.DoubaoSearchMode
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.ui.components.FormItem
import me.rerere.ui.components.OutlinedNumberInput
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(updated: SearchServiceOptions) {
        options = updated
        val newServices = settings.searchServices.toMutableList()
        newServices[serviceIndex] = updated
        vm.updateSettings { it.copy(searchServices = newServices) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.searchServices.size > 1) {
                        IconButton(
                            onClick = {
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(serviceIndex)
                                vm.updateSettings { it.copy(searchServices = newServices) }
                                nav.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_search_config),
                            style = MaterialTheme.typography.titleMedium
                        )

                        SearchServiceOptionsEditor(
                            options = options,
                            onUpdateOptions = { save(it) }
                        )

                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            SearchService.getService(options).Description()
                        }
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DoubaoOptions -> {
            DoubaoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DeepSeekOptions -> {
            DeepSeekOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DeepSeekWebOptions -> {
            DeepSeekWebOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_test),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(
                                    mapOf("query" to JsonPrimitive(query))
                                )
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run)
                        )
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }

    FormItem(
        label = {
            Text("Include Answer")
        },
        description = {
            Text("Let Tavily generate an answer summary alongside the search results")
        },
        tail = {
            Switch(
                checked = options.includeAnswer,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(includeAnswer = checked))
                }
            )
        }
    )
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun DoubaoOptions(
    options: SearchServiceOptions.DoubaoOptions,
    onUpdateOptions: (SearchServiceOptions.DoubaoOptions) -> Unit
) {
    FormItem(label = { Text(stringResource(R.string.search_detail_api_key)) }) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions(options.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(label = { Text("Mode") }) {
        val modes = DoubaoSearchMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    onClick = { onUpdateOptions(options.copy(mode = mode)) },
                    selected = options.mode == mode
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_url))
        }
    ) {
        OutlinedTextField(
            value = options.url,
            onValueChange = {
                onUpdateOptions(options.copy(url = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_engines))
        }
    ) {
        OutlinedTextField(
            value = options.engines,
            onValueChange = {
                onUpdateOptions(options.copy(engines = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_language))
        }
    ) {
        OutlinedTextField(
            value = options.language,
            onValueChange = {
                onUpdateOptions(options.copy(language = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_username))
        }
    ) {
        OutlinedTextField(
            value = options.username,
            onValueChange = {
                onUpdateOptions(options.copy(username = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_password))
        }
    ) {
        OutlinedTextField(
            value = options.password,
            onValueChange = {
                onUpdateOptions(options.copy(password = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun DeepSeekOptions(
    options: SearchServiceOptions.DeepSeekOptions,
    onUpdateOptions: (SearchServiceOptions.DeepSeekOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun DeepSeekWebOptions(
    options: SearchServiceOptions.DeepSeekWebOptions,
    onUpdateOptions: (SearchServiceOptions.DeepSeekWebOptions) -> Unit
) {
    var showLoginDialog by remember { mutableStateOf(false) }

    FilledTonalButton(
        onClick = { showLoginDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = HugeIcons.Login03,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("网页登录 DeepSeek 并自动获取 Token")
    }

    FormItem(
        label = {
            Text("User Token")
        },
        description = {
            Text("必填，支持直接粘贴纯 Token、Bearer 前缀，或 localStorage.userToken 的 JSON")
        }
    ) {
        OutlinedTextField(
            value = options.token,
            onValueChange = {
                onUpdateOptions(options.copy(token = it))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("粘贴 Token 或 {\"value\":\"...\"}")
            }
        )
    }

    FormItem(
        label = {
            Text("Cookies（可选）")
        },
        description = {
            Text("可选，浏览器抓包的 Cookie 头字符串；如遇 WAF 验证建议通过上方网页登录刷新")
        }
    ) {
        OutlinedTextField(
            value = options.cookies,
            onValueChange = {
                onUpdateOptions(options.copy(cookies = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            placeholder = {
                Text("cf_clearance=...; ...")
            }
        )
    }

    FormItem(
        label = {
            Text("深度搜索（DeepSearch）")
        },
        description = {
            Text("关闭时为快速联网搜索总结；开启后启用深度思考与多轮网页精读，耗时更长")
        },
        tail = {
            Switch(
                checked = options.deepSearch,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(deepSearch = checked))
                }
            )
        }
    )

    FormItem(
        label = {
            Text("最小请求间隔（秒）")
        },
        description = {
            Text("两次搜索之间的最小冷却间隔（建议 ≥ 10 秒），自动排队限速，防止高频触发风控或禁言")
        }
    ) {
        OutlinedNumberInput(
            value = options.minIntervalSeconds,
            onValueChange = { value ->
                onUpdateOptions(options.copy(minIntervalSeconds = value.coerceIn(0, 600)))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("每小时最大请求数")
        },
        description = {
            Text("滑动 1 小时窗口内的最大搜索次数（0 表示不限制，建议 15~30 次）")
        }
    ) {
        OutlinedNumberInput(
            value = options.maxRequestsPerHour,
            onValueChange = { value ->
                onUpdateOptions(options.copy(maxRequestsPerHour = value.coerceIn(0, 1000)))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("补充搜索说明（可选）")
        },
        description = {
            Text(
                "默认留空，仅发送自然搜索词。严禁填写大段提示词或工具调用标签，否则易被官方判定为违规反代（最多 ${SearchServiceOptions.DeepSeekWebOptions.MAX_CUSTOM_PROMPT_LENGTH} 字）。"
            )
        }
    ) {
        OutlinedTextField(
            value = options.customPrompt,
            onValueChange = { value ->
                val limited = value.take(SearchServiceOptions.DeepSeekWebOptions.MAX_CUSTOM_PROMPT_LENGTH)
                onUpdateOptions(options.copy(customPrompt = limited))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            placeholder = {
                Text("留空即可（例如：简明列出关键事实与时间）")
            }
        )
    }

    if (showLoginDialog) {
        DeepSeekWebLoginDialog(
            onDismiss = { showLoginDialog = false },
            onCredentialsExtracted = { token, cookies ->
                showLoginDialog = false
                onUpdateOptions(
                    options.copy(
                        token = token,
                        cookies = cookies.ifBlank { options.cookies }
                    )
                )
            }
        )
    }
}

@Composable
private fun DeepSeekWebLoginDialog(
    onDismiss: () -> Unit,
    onCredentialsExtracted: (token: String, cookies: String) -> Unit,
) {
    val webViewState = rememberWebViewState(
        url = "https://chat.deepseek.com",
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
        },
    )
    var statusText by remember { mutableStateOf("请登录 DeepSeek，登录成功后将自动提取 Token 与 Cookie") }
    var extracted by remember { mutableStateOf(false) }

    fun tryExtract(manual: Boolean) {
        val webView = webViewState.webView ?: return
        if (extracted) return
        webView.evaluateJavascript("localStorage.getItem('userToken')") { rawResult ->
            val token = if (rawResult == null || rawResult == "null") {
                ""
            } else {
                DeepSeekWebSearchService.normalizeToken(rawResult)
            }
            if (token.isNotBlank() && !extracted) {
                extracted = true
                val cookieManager = CookieManager.getInstance()
                cookieManager.flush()
                val cookies = mergeCookieHeaders(
                    cookieManager.getCookie("https://chat.deepseek.com"),
                    cookieManager.getCookie("https://deepseek.com"),
                )
                onCredentialsExtracted(token, cookies)
            } else if (manual) {
                statusText = "尚未检测到登录状态，请先完成 DeepSeek 登录后再点击提取"
            }
        }
    }

    LaunchedEffect(webViewState.webView, extracted) {
        while (!extracted) {
            delay(1500)
            val currentUrl = webViewState.currentUrl.orEmpty()
            if (currentUrl.contains("chat.deepseek.com", ignoreCase = true) &&
                !currentUrl.contains("/sign_in", ignoreCase = true)
            ) {
                tryExtract(manual = false)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("登录 DeepSeek") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { webViewState.reload() }) {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = "刷新"
                                )
                            }
                            TextButton(onClick = { tryExtract(manual = true) }) {
                                Icon(
                                    imageVector = HugeIcons.Tick02,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("提取")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            ) { innerPadding ->
                WebView(
                    state = webViewState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    onCreated = { webView ->
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webView, true)
                        }
                    }
                )
            }
        }
    }
}

private fun mergeCookieHeaders(vararg headers: String?): String {
    val map = LinkedHashMap<String, String>()
    for (header in headers) {
        if (header.isNullOrBlank()) continue
        for (part in header.split(';')) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) continue
            val name = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (name.isNotEmpty()) {
                map[name] = value
            }
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

@Composable
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens))
        }
    ) {
        OutlinedTextField(
            value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(options.copy(maxTokens = value.toIntOrNull()))
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens_per_page))
        }
    ) {
        OutlinedTextField(
            value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(options.copy(maxTokensPerPage = value.toIntOrNull()))
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_url))
        }
    ) {
        OutlinedTextField(
            value = options.searchUrl,
            onValueChange = {
                onUpdateOptions(options.copy(searchUrl = it.trim()))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            }
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_url))
        }
    ) {
        OutlinedTextField(
            value = options.scrapeUrl,
            onValueChange = {
                onUpdateOptions(options.copy(scrapeUrl = it.trim()))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            }
        )
    }
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(summary = checked))
                }
            )
        }
    )
}

@Composable
internal fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: (SearchServiceOptions.TinyfishOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_model))
        }
    ) {
        OutlinedTextField(
            value = options.model,
            onValueChange = {
                onUpdateOptions(options.copy(model = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_custom_url))
        }
    ) {
        OutlinedTextField(
            value = options.customUrl,
            onValueChange = {
                onUpdateOptions(options.copy(customUrl = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_system_prompt))
        }
    ) {
        OutlinedTextField(
            value = options.systemPrompt,
            onValueChange = {
                onUpdateOptions(options.copy(systemPrompt = it))
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: (SearchServiceOptions.CustomJsOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_name))
        }
    ) {
        OutlinedTextField(
            value = options.name,
            onValueChange = {
                onUpdateOptions(options.copy(name = it))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_detail_custom_search_placeholder)) }
        )
    }

    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        }
    ) {
        OutlinedTextField(
            value = options.searchScript,
            onValueChange = {
                onUpdateOptions(options.copy(searchScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 20,
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        }
    ) {
        OutlinedTextField(
            value = options.scrapeScript,
            onValueChange = {
                onUpdateOptions(options.copy(scrapeScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 20,
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }
}
