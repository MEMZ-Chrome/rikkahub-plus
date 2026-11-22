package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Tap01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.phoneuse.PhoneUseBackend
import me.rerere.rikkahub.phoneuse.PhoneUseManager
import me.rerere.rikkahub.phoneuse.isPhoneUseCheckedInSystem
import me.rerere.rikkahub.phoneuse.isFloatingChatWindowOn
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openAccessibilitySettings
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import rikka.shizuku.Shizuku

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
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
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showBlockedApps by remember { mutableStateOf(false) }
    var showBackendSheet by remember { mutableStateOf(false) }
    val phoneUseManager: PhoneUseManager = koinInject()
    val phoneUseControllers by phoneUseManager.controllers.collectAsStateWithLifecycle()
    val phoneUseBackend = settings.phoneUseBackend
    // 选中的执行方式现在能不能用：无障碍服务没开、Shizuku 没授权、su 不给都会是 false
    val phoneUseServiceOn = phoneUseControllers.containsKey(phoneUseBackend)
    val shizukuInstalled = remember(context) {
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
    }
    // 系统设置里勾选着但无障碍服务没连上：被系统停掉了，要关掉重开。从系统设置或 Shizuku 回来时重新检查
    var phoneUseCheckedInSystem by remember { mutableStateOf(isPhoneUseCheckedInSystem(context)) }
    LifecycleResumeEffect(Unit) {
        phoneUseCheckedInSystem = isPhoneUseCheckedInSystem(context)
        scope.launch { phoneUseManager.refreshShells() }
        onPauseOrDispose {}
    }
    val phoneUseServiceStopped = !phoneUseControllers.containsKey(PhoneUseBackend.ACCESSIBILITY) && phoneUseCheckedInSystem

    // 用户在 Shizuku 的授权框里点了允许之后刷新状态
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                scope.launch { phoneUseManager.refreshShells() }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)
    val floatingWindowRequiredText =
        stringResource(R.string.assistant_page_local_tools_phone_use_floating_window_required)
    val chooseBackendText = stringResource(R.string.assistant_page_local_tools_phone_use_choose_backend)
    val backendNotReadyText = stringResource(R.string.assistant_page_local_tools_phone_use_backend_not_ready_toast)
    val enableServiceText = stringResource(R.string.assistant_page_local_tools_phone_use_enable_service)
    val restartServiceText = stringResource(R.string.assistant_page_local_tools_phone_use_restart_service)
    // Android 11 以下没有无障碍截图接口，用户授权录屏后才有画面
    val screenCaptureActive = phoneUseManager.screenCaptureActive.collectAsStateWithLifecycle().value
    val screenCaptureRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    val screenCaptureDeniedText =
        stringResource(R.string.assistant_page_local_tools_phone_use_screen_capture_denied)
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null ||
            !phoneUseManager.startScreenCapture(result.resultCode, data)
        ) {
            toaster.show(message = screenCaptureDeniedText, type = ToastType.Warning)
        }
    }

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        // 没有悬浮窗就看不到 AI 在做什么、也不方便随时停下，先去把它打开，这次不开启工具
        if (enabled && option == LocalToolOption.PhoneUse &&
            !isFloatingChatWindowOn(context, settingsStore.settingsFlow.value)
        ) {
            toaster.show(message = floatingWindowRequiredText, type = ToastType.Warning)
            navController.navigate(Screen.SettingPreferencesNotification)
            return
        }
        // 打开 Phone Use 时先让用户挑执行方式，别一上来就把人丢进无障碍设置；
        // 挑完在弹层里再去开对应的权限
        if (enabled && option == LocalToolOption.PhoneUse && !phoneUseServiceOn) {
            toaster.show(message = chooseBackendText, type = ToastType.Warning)
            showBackendSheet = true
        }
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        title: @Composable () -> Unit,
        description: @Composable () -> Unit,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            // 图标底色跟着开关状态变化
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = description,
            headlineContent = title,
        )
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        @StringRes title: Int,
        @StringRes description: Int,
    ) = toolItem(
        option = option,
        icon = icon,
        title = { Text(stringResource(title)) },
        description = { Text(stringResource(description)) },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            toolItem(
                option = LocalToolOption.JavascriptEngine,
                icon = HugeIcons.JavaScript,
                title = R.string.assistant_page_local_tools_javascript_engine_title,
                description = R.string.assistant_page_local_tools_javascript_engine_desc,
            )
            toolItem(
                option = LocalToolOption.TimeInfo,
                icon = HugeIcons.Clock01,
                title = R.string.assistant_page_local_tools_time_info_title,
                description = R.string.assistant_page_local_tools_time_info_desc,
            )
            toolItem(
                option = LocalToolOption.Clipboard,
                icon = HugeIcons.ClipboardPaste,
                title = R.string.assistant_page_local_tools_clipboard_title,
                description = R.string.assistant_page_local_tools_clipboard_desc,
            )
            toolItem(
                option = LocalToolOption.Tts,
                icon = HugeIcons.VolumeHigh,
                title = R.string.assistant_page_local_tools_tts_title,
                description = R.string.assistant_page_local_tools_tts_desc,
            )
            toolItem(
                option = LocalToolOption.AskUser,
                icon = HugeIcons.MessageQuestion,
                title = R.string.assistant_page_local_tools_ask_user_title,
                description = R.string.assistant_page_local_tools_ask_user_desc,
            )
            toolItem(
                option = LocalToolOption.ScreenTime,
                icon = HugeIcons.SmartPhone01,
                title = R.string.assistant_page_local_tools_screen_time_title,
                description = R.string.assistant_page_local_tools_screen_time_desc,
            )
            toolItem(
                option = LocalToolOption.Calendar,
                icon = HugeIcons.Calendar03,
                title = R.string.assistant_page_local_tools_calendar_title,
                description = R.string.assistant_page_local_tools_calendar_desc,
            )
            toolItem(
                option = LocalToolOption.ChartDisplay,
                icon = HugeIcons.ChartLineData01,
                title = R.string.assistant_page_local_tools_chart_display_title,
                description = R.string.assistant_page_local_tools_chart_display_desc,
            )
            // 只有 github 渠道才有
            if (PhoneUseManager.isSupported) {
                toolItem(
                    option = LocalToolOption.PhoneUse,
                    icon = HugeIcons.Tap01,
                    title = { Text(stringResource(R.string.assistant_page_local_tools_phone_use_title)) },
                    description = {
                        Column {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_desc))
                            // 开了工具但选中的执行方式用不了时模型调用会直接报错，在这里说明原因
                            if (LocalToolOption.PhoneUse in assistant.localTools && !phoneUseServiceOn) {
                                Text(
                                    text = stringResource(
                                        when {
                                            phoneUseBackend == PhoneUseBackend.ACCESSIBILITY && phoneUseServiceStopped ->
                                                R.string.assistant_page_local_tools_phone_use_service_stopped

                                            phoneUseBackend == PhoneUseBackend.ACCESSIBILITY ->
                                                R.string.assistant_page_local_tools_phone_use_service_off

                                            else ->
                                                R.string.assistant_page_local_tools_phone_use_backend_off
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
                // 怎么操作手机：无障碍、Shizuku、root 三选一
                if (LocalToolOption.PhoneUse in assistant.localTools) {
                    item(
                        onClick = { showBackendSheet = true },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Settings02,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_backend_desc))
                        },
                        trailingContent = {
                            Text(
                                text = when (phoneUseBackend) {
                                    PhoneUseBackend.ACCESSIBILITY ->
                                        stringResource(R.string.assistant_page_local_tools_phone_use_backend_accessibility)

                                    PhoneUseBackend.SHIZUKU ->
                                        stringResource(R.string.assistant_page_local_tools_phone_use_backend_shizuku)

                                    PhoneUseBackend.ROOT ->
                                        stringResource(R.string.assistant_page_local_tools_phone_use_backend_root)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (phoneUseServiceOn) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_backend))
                        },
                    )
                }
                // Android 11 以下没有无障碍截图接口，在这里让用户手动授权录屏取帧；
                // Shizuku / root 用 screencap，不需要这个
                if (screenCaptureRequired && LocalToolOption.PhoneUse in assistant.localTools &&
                    phoneUseBackend == PhoneUseBackend.ACCESSIBILITY
                ) {
                    switchItem(
                        checked = screenCaptureActive,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                context.getSystemService(MediaProjectionManager::class.java)
                                    ?.let { screenCaptureLauncher.launch(it.createScreenCaptureIntent()) }
                            } else {
                                phoneUseManager.stopScreenCapture()
                            }
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Camera01,
                                containerColor = if (screenCaptureActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (screenCaptureActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_screen_capture_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_screen_capture_title))
                        },
                    )
                }
                // 哪些应用不给 AI 操作由用户自己决定，默认一个都不禁
                if (LocalToolOption.PhoneUse in assistant.localTools) {
                    item(
                        onClick = { showBlockedApps = true },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Cancel01,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_desc))
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_local_tools_phone_use_blocked_apps_count,
                                    settings.phoneUseBlockedApps.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps))
                        },
                    )
                }
            }
        }
    }

    if (showBlockedApps) {
        PhoneUseBlockedAppsSheet(
            blocked = settings.phoneUseBlockedApps.toSet(),
            onToggle = { packageName, blocked ->
                scope.launch {
                    settingsStore.update { current ->
                        current.copy(
                            phoneUseBlockedApps = if (blocked) {
                                (current.phoneUseBlockedApps + packageName).distinct()
                            } else {
                                current.phoneUseBlockedApps - packageName
                            }
                        )
                    }
                }
            },
            onDismiss = { showBlockedApps = false },
        )
    }

    if (showBackendSheet) {
        PhoneUseBackendSheet(
            selected = phoneUseBackend,
            controllers = phoneUseControllers,
            shizukuInstalled = shizukuInstalled,
            accessibilityStopped = phoneUseServiceStopped,
            onSelect = { backend ->
                showBackendSheet = false
                scope.launch {
                    settingsStore.update { it.copy(phoneUseBackend = backend) }
                    // 换完立刻探一次，返回页面时状态就是准的
                    phoneUseManager.refreshShells()
                }
            },
            onOpenAccessibility = {
                showBackendSheet = false
                toaster.show(
                    message = if (phoneUseServiceStopped) restartServiceText else enableServiceText,
                    type = ToastType.Warning,
                )
                context.openAccessibilitySettings()
            },
            onAuthorizeShizuku = {
                showBackendSheet = false
                // 没装或者没启动时 requestPermission 会抛，提示用户先开 Shizuku
                val requested = runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }.isSuccess
                if (!requested) {
                    toaster.show(message = backendNotReadyText, type = ToastType.Warning)
                }
            },
            onInstallShizuku = {
                showBackendSheet = false
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, SHIZUKU_URL.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            // root 要重新问一次 su，用户可能刚在 root 管理里点了允许
            onRecheck = {
                showBackendSheet = false
                scope.launch { phoneUseManager.refreshShells(force = true) }
            },
            onDismiss = { showBackendSheet = false },
        )
    }
}

private const val SHIZUKU_REQUEST_CODE = 4213
private const val SHIZUKU_PACKAGE = "moe.shizuku.manager"
private const val SHIZUKU_URL = "https://shizuku.rikka.app/"
