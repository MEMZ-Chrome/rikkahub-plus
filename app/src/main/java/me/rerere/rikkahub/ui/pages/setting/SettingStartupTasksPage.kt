package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.StartupTask
import me.rerere.rikkahub.data.datastore.StartupTaskTrigger
import me.rerere.rikkahub.data.datastore.StartupTasksConfig
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellLogTail
import me.rerere.rikkahub.service.StartupTaskManager
import me.rerere.rikkahub.service.StartupTaskRunStatus
import me.rerere.rikkahub.service.StartupTaskRuntimeState
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.extensions.workspace.statusLabel
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LOG_POLL_INTERVAL_MS = 1_000L
private val AUTO_RUN_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
fun SettingStartupTasksPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val startupTaskManager: StartupTaskManager = koinInject()
    val workspaces by remember(workspaceRepository) {
        workspaceRepository.listFlow()
    }.collectAsStateWithLifecycle(emptyList())
    val runtimeStates by startupTaskManager.states.collectAsStateWithLifecycle()

    val config = settings.startupTasksConfig
    val workspacesById = remember(workspaces) { workspaces.associateBy { it.id } }
    val defaultWorkspace = config.defaultWorkspaceId?.let { workspacesById[it] }
    val runningCount = remember(config.tasks, runtimeStates) {
        config.tasks.count { runtimeStates[it.id]?.status == StartupTaskRunStatus.RUNNING }
    }

    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showDefaultWorkspacePicker by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<StartupTask?>(null) }
    var creatingTask by remember { mutableStateOf(false) }
    var logTarget by remember { mutableStateOf<StartupTask?>(null) }
    var deleteTarget by remember { mutableStateOf<StartupTask?>(null) }
    var stoppingIds by remember { mutableStateOf(emptySet<String>()) }

    val startedToastFormat = stringResource(R.string.setting_startup_tasks_started_toast)
    val resetDoneToast = stringResource(R.string.setting_startup_tasks_reset_schedule_done)

    val now by produceState(System.currentTimeMillis(), runningCount) {
        value = System.currentTimeMillis()
        while (runningCount > 0) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }

    fun updateConfig(transform: (StartupTasksConfig) -> StartupTasksConfig) {
        vm.updateSettings { latest ->
            latest.copy(startupTasksConfig = transform(latest.startupTasksConfig))
        }
    }

    fun runTaskNow(task: StartupTask) {
        scope.launch {
            startupTaskManager.runNow(task, config.defaultWorkspaceId)
                .onSuccess {
                    toaster.show(startedToastFormat.format(task.displayName()), type = ToastType.Success)
                }
                .onFailure { error ->
                    toaster.show(error.message.orEmpty().ifBlank { error.javaClass.simpleName }, type = ToastType.Error)
                }
        }
    }

    fun stopTask(taskId: String) {
        if (taskId in stoppingIds) return
        stoppingIds = stoppingIds + taskId
        scope.launch {
            try {
                startupTaskManager.stop(taskId)
            } finally {
                stoppingIds = stoppingIds - taskId
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_startup_tasks)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creatingTask = true }) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_startup_tasks_add),
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("defaultWorkspace") {
                val defaultWorkspaceNotReady = defaultWorkspace != null &&
                    !defaultWorkspace.isSsh() &&
                    defaultWorkspace.shellStatus != WorkspaceShellStatus.READY.name

                CardGroup(
                    title = { Text(stringResource(R.string.setting_startup_tasks_default_workspace_title)) },
                ) {
                    item(
                        onClick = { showDefaultWorkspacePicker = true },
                        leadingContent = {
                            CardGroupIcon(
                                icon = if (defaultWorkspace?.isSsh() == true) {
                                    HugeIcons.CloudServer
                                } else {
                                    HugeIcons.FolderCode
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = when {
                                    defaultWorkspace != null -> defaultWorkspace.name
                                    !config.defaultWorkspaceId.isNullOrBlank() ->
                                        stringResource(R.string.setting_startup_tasks_workspace_missing)
                                    else -> stringResource(R.string.setting_startup_tasks_no_default_workspace)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = when {
                                    defaultWorkspace != null -> defaultWorkspace.statusLabel()
                                    else -> stringResource(R.string.setting_startup_tasks_no_default_workspace_desc)
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )

                    if (defaultWorkspaceNotReady && defaultWorkspace != null) {
                        item(
                            onClick = { navController.navigate(Screen.WorkspaceDetail(defaultWorkspace.id)) },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Alert01,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_startup_tasks_open_workspace),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_startup_tasks_workspace_not_ready_hint))
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    } else if (defaultWorkspace?.isSsh() == true) {
                        item(
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.CloudServer,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_startup_tasks_ssh_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }

            if (config.tasks.isEmpty()) {
                item("empty") {
                    ExtensionEmptyState(
                        message = stringResource(R.string.setting_startup_tasks_empty),
                        description = stringResource(R.string.setting_startup_tasks_empty_desc),
                        icon = HugeIcons.ComputerTerminal01,
                    )
                }
            } else {
                item("tasksSection") {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_startup_tasks_section_tasks),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (runningCount > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_startup_tasks_running_count, runningCount),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            config.tasks.forEachIndexed { index, task ->
                                val runtime = runtimeStates[task.id]
                                StartupTaskCard(
                                    task = task,
                                    runtime = runtime,
                                    now = now,
                                    stopping = task.id in stoppingIds,
                                    defaultWorkspace = defaultWorkspace,
                                    workspacesById = workspacesById,
                                    shape = cardGroupItemShape(index, config.tasks.size),
                                    onClick = { editingTask = task },
                                    onToggleEnabled = { enabled ->
                                        updateConfig { current ->
                                            current.copy(
                                                tasks = current.tasks.map {
                                                    if (it.id == task.id) it.copy(enabled = enabled) else it
                                                }
                                            )
                                        }
                                    },
                                    onRunNow = { runTaskNow(task) },
                                    onStop = { stopTask(task.id) },
                                    onViewLog = { logTarget = task },
                                    onResetSchedule = {
                                        scope.launch {
                                            startupTaskManager.resetAutoRunRecord(task.id)
                                            toaster.show(resetDoneToast)
                                        }
                                    },
                                    onDelete = { deleteTarget = task },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDefaultWorkspacePicker) {
        StartupWorkspacePickerSheet(
            selectedWorkspaceId = config.defaultWorkspaceId,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                updateConfig { it.copy(defaultWorkspaceId = workspaceId) }
                showDefaultWorkspacePicker = false
            },
            onManage = {
                showDefaultWorkspacePicker = false
                navController.navigate(Screen.Workspaces)
            },
            onDismiss = { showDefaultWorkspacePicker = false },
        )
    }

    if (creatingTask || editingTask != null) {
        val currentEditing = editingTask
        StartupTaskEditorSheet(
            initialTask = currentEditing,
            defaultWorkspace = defaultWorkspace,
            workspaces = workspaces,
            onDismiss = {
                creatingTask = false
                editingTask = null
            },
            onSave = { saved ->
                updateConfig { current ->
                    val exists = current.tasks.any { it.id == saved.id }
                    val updatedList = if (exists) {
                        current.tasks.map { if (it.id == saved.id) saved else it }
                    } else {
                        current.tasks + saved
                    }
                    current.copy(tasks = updatedList)
                }
                creatingTask = false
                editingTask = null
            },
        )
    }

    logTarget?.let { target ->
        val latestTask = config.tasks.find { it.id == target.id } ?: target
        val runtime = runtimeStates[latestTask.id]
        StartupTaskLogSheet(
            task = latestTask,
            runtime = runtime,
            now = now,
            stopping = latestTask.id in stoppingIds,
            startupTaskManager = startupTaskManager,
            onRunNow = { runTaskNow(latestTask) },
            onStop = { stopTask(latestTask.id) },
            onDismiss = { logTarget = null },
        )
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.confirm_delete),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val taskId = target.id
                deleteTarget = null
                updateConfig { current ->
                    current.copy(tasks = current.tasks.filterNot { it.id == taskId })
                }
                scope.launch {
                    startupTaskManager.removeTaskState(taskId)
                }
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.setting_startup_tasks_delete_confirm, target.displayName()))
        }
    }
}

@Composable
private fun StartupTaskCard(
    task: StartupTask,
    runtime: StartupTaskRuntimeState?,
    now: Long,
    stopping: Boolean,
    defaultWorkspace: WorkspaceEntity?,
    workspacesById: Map<String, WorkspaceEntity>,
    shape: Shape,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onStop: () -> Unit,
    onViewLog: () -> Unit,
    onResetSchedule: () -> Unit,
    onDelete: () -> Unit,
) {
    val isRunning = runtime?.status == StartupTaskRunStatus.RUNNING
    val effectiveWorkspace = if (!task.workspaceId.isNullOrBlank()) {
        workspacesById[task.workspaceId]
    } else {
        defaultWorkspace
    }
    val workspaceMissing = !task.workspaceId.isNullOrBlank() && effectiveWorkspace == null
    val workspaceLabel = when {
        !task.workspaceId.isNullOrBlank() && effectiveWorkspace != null -> effectiveWorkspace.name
        workspaceMissing -> stringResource(R.string.setting_startup_tasks_workspace_missing)
        defaultWorkspace != null -> stringResource(
            R.string.setting_startup_tasks_workspace_follow_default_named,
            defaultWorkspace.name,
        )
        else -> stringResource(R.string.setting_startup_tasks_workspace_unset)
    }
    val workspaceHasWarning = effectiveWorkspace == null ||
        (!effectiveWorkspace.isSsh() && effectiveWorkspace.shellStatus != WorkspaceShellStatus.READY.name)

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StartupTaskStatusIcon(runtime = runtime, enabled = task.enabled)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = task.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (task.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = runtime.statusSummary(now),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (runtime?.status == StartupTaskRunStatus.FAILED ||
                            (runtime?.status == StartupTaskRunStatus.EXITED && runtime.exitCode != 0)
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = task.command.trim().replace("\n", " ; "),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaPill(
                        icon = task.trigger.icon(),
                        text = task.trigger.shortLabel(),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    MetaPill(
                        icon = if (effectiveWorkspace?.isSsh() == true) HugeIcons.CloudServer else HugeIcons.FolderCode,
                        text = workspaceLabel,
                        containerColor = if (workspaceHasWarning) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (workspaceHasWarning) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (isRunning) onStop() else onRunNow() },
                        enabled = !stopping,
                    ) {
                        if (stopping) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else if (isRunning) {
                            Icon(
                                imageVector = HugeIcons.StopCircle,
                                contentDescription = stringResource(R.string.stop),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Icon(
                                imageVector = HugeIcons.Play,
                                contentDescription = stringResource(R.string.setting_startup_tasks_run_now),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    IconButton(onClick = onViewLog) {
                        Icon(
                            imageVector = HugeIcons.FileView,
                            contentDescription = stringResource(R.string.setting_startup_tasks_view_log),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val canResetSchedule = task.trigger != StartupTaskTrigger.EVERY_LAUNCH &&
                        runtime?.lastAutoRunAt != null
                    val actions = buildList {
                        add(
                            ItemAction(
                                text = stringResource(R.string.edit),
                                icon = HugeIcons.PencilEdit01,
                                onClick = onClick,
                            )
                        )
                        if (canResetSchedule) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.setting_startup_tasks_reset_schedule),
                                    icon = HugeIcons.Refresh01,
                                    onClick = onResetSchedule,
                                )
                            )
                        }
                        add(
                            ItemAction(
                                text = stringResource(R.string.delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            )
                        )
                    }
                    ItemActionMenu(actions = actions)
                }
            }
        }
    }
}

@Composable
private fun MetaPill(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StartupTaskStatusIcon(
    runtime: StartupTaskRuntimeState?,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val (icon, container, content) = when {
        runtime?.status == StartupTaskRunStatus.RUNNING ->
            Triple(HugeIcons.ComputerTerminal01, colors.primaryContainer, colors.onPrimaryContainer)

        runtime?.status == StartupTaskRunStatus.FAILED ->
            Triple(HugeIcons.Alert01, colors.errorContainer, colors.onErrorContainer)

        runtime?.status == StartupTaskRunStatus.KILLED ->
            Triple(HugeIcons.Cancel01, colors.surfaceContainerHighest, colors.onSurfaceVariant)

        runtime?.status == StartupTaskRunStatus.EXITED && runtime.exitCode == 0 ->
            Triple(HugeIcons.Tick01, colors.secondaryContainer, colors.onSecondaryContainer)

        runtime?.status == StartupTaskRunStatus.EXITED ->
            Triple(HugeIcons.Alert01, colors.errorContainer, colors.onErrorContainer)

        !enabled ->
            Triple(HugeIcons.ComputerTerminal01, colors.surfaceContainerHighest, colors.onSurfaceVariant)

        else ->
            Triple(HugeIcons.ComputerTerminal01, colors.secondaryContainer, colors.onSecondaryContainer)
    }
    CardGroupIcon(icon = icon, containerColor = container, contentColor = content)
}

@Composable
private fun StartupWorkspacePickerSheet(
    selectedWorkspaceId: String?,
    workspaces: List<WorkspaceEntity>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_startup_tasks_default_workspace_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WorkspaceOptionRow(
                    title = stringResource(R.string.setting_startup_tasks_no_default_workspace),
                    selected = selectedWorkspaceId.isNullOrBlank(),
                    icon = HugeIcons.Codesandbox,
                    onClick = { onSelect(null) },
                )
                workspaces.forEach { workspace ->
                    WorkspaceOptionRow(
                        title = workspace.name,
                        status = workspace.statusLabel(),
                        selected = workspace.id == selectedWorkspaceId,
                        icon = if (workspace.isSsh()) HugeIcons.CloudServer else HugeIcons.FolderCode,
                        onClick = { onSelect(workspace.id) },
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ListItem(
                leadingContent = { Icon(HugeIcons.Codesandbox, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.workspace_manage)) },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onManage),
            )
        }
    }
}

@Composable
private fun WorkspaceOptionRow(
    title: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    status: String? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = status?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            }
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun StartupTaskEditorSheet(
    initialTask: StartupTask?,
    defaultWorkspace: WorkspaceEntity?,
    workspaces: List<WorkspaceEntity>,
    onDismiss: () -> Unit,
    onSave: (StartupTask) -> Unit,
) {
    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current
    val workspacesById = remember(workspaces) { workspaces.associateBy { it.id } }

    var name by remember(initialTask?.id) { mutableStateOf(initialTask?.name.orEmpty()) }
    var command by remember(initialTask?.id) { mutableStateOf(initialTask?.command.orEmpty()) }
    var trigger by remember(initialTask?.id) { mutableStateOf(initialTask?.trigger ?: StartupTaskTrigger.EVERY_LAUNCH) }
    var workspaceId by remember(initialTask?.id) { mutableStateOf(initialTask?.workspaceId) }
    var cwd by remember(initialTask?.id) { mutableStateOf(initialTask?.cwd.orEmpty()) }
    var enabled by remember(initialTask?.id) { mutableStateOf(initialTask?.enabled ?: true) }
    var workspaceExpanded by remember { mutableStateOf(false) }

    val selectedWorkspace = workspaceId?.let { workspacesById[it] }
    val selectedWorkspaceText = when {
        !workspaceId.isNullOrBlank() && selectedWorkspace != null -> selectedWorkspace.name
        !workspaceId.isNullOrBlank() -> stringResource(R.string.setting_startup_tasks_workspace_missing)
        defaultWorkspace != null -> stringResource(
            R.string.setting_startup_tasks_workspace_follow_default_named,
            defaultWorkspace.name,
        )
        else -> stringResource(R.string.setting_startup_tasks_workspace_follow_default)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (initialTask == null) R.string.setting_startup_tasks_add else R.string.setting_startup_tasks_edit
                ),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_startup_tasks_name_label)) },
                placeholder = { Text(stringResource(R.string.setting_startup_tasks_name_placeholder)) },
                singleLine = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_startup_tasks_trigger_label),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                ConnectedChoiceButtons(
                    options = StartupTaskTrigger.entries,
                    selected = trigger,
                    onSelect = { trigger = it },
                ) { option, selected ->
                    ConnectedChoiceLabel(
                        text = option.shortLabel(),
                        selected = selected,
                        icon = option.icon(),
                    )
                }
                Text(
                    text = trigger.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.setting_startup_tasks_workspace_label),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    onClick = { workspaceExpanded = !workspaceExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (selectedWorkspace?.isSsh() == true) {
                                HugeIcons.CloudServer
                            } else {
                                HugeIcons.FolderCode
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedWorkspaceText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val effective = selectedWorkspace ?: defaultWorkspace
                            if (effective != null) {
                                Text(
                                    text = effective.statusLabel(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            imageVector = if (workspaceExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(visible = workspaceExpanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            WorkspaceOptionRow(
                                title = if (defaultWorkspace != null) {
                                    stringResource(
                                        R.string.setting_startup_tasks_workspace_follow_default_named,
                                        defaultWorkspace.name,
                                    )
                                } else {
                                    stringResource(R.string.setting_startup_tasks_workspace_follow_default)
                                },
                                selected = workspaceId.isNullOrBlank(),
                                icon = HugeIcons.Codesandbox,
                                onClick = {
                                    workspaceId = null
                                    workspaceExpanded = false
                                },
                            )
                            workspaces.forEach { workspace ->
                                WorkspaceOptionRow(
                                    title = workspace.name,
                                    status = workspace.statusLabel(),
                                    selected = workspace.id == workspaceId,
                                    icon = if (workspace.isSsh()) HugeIcons.CloudServer else HugeIcons.FolderCode,
                                    onClick = {
                                        workspaceId = workspace.id
                                        workspaceExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_startup_tasks_command_label)) },
                placeholder = { Text(stringResource(R.string.setting_startup_tasks_command_placeholder)) },
                minLines = 4,
                maxLines = 10,
                visualTransformation = HighlightCodeVisualTransformation(
                    language = "bash",
                    highlighter = highlighter,
                    darkMode = darkMode,
                ),
                textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
            )

            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_startup_tasks_cwd_label)) },
                placeholder = { Text(stringResource(R.string.setting_startup_tasks_cwd_placeholder)) },
                singleLine = true,
            )

            Surface(
                onClick = { enabled = !enabled },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.setting_startup_tasks_enabled_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = {
                        val trimmedCommand = command.trim()
                        val resolvedName = name.trim().ifBlank {
                            trimmedCommand.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32).orEmpty()
                        }
                        val task = (initialTask ?: StartupTask()).copy(
                            name = resolvedName,
                            command = trimmedCommand,
                            trigger = trigger,
                            enabled = enabled,
                            workspaceId = workspaceId?.takeIf { it.isNotBlank() },
                            cwd = cwd.trim(),
                        )
                        onSave(task)
                    },
                    enabled = command.isNotBlank(),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

@Composable
private fun StartupTaskLogSheet(
    task: StartupTask,
    runtime: StartupTaskRuntimeState?,
    now: Long,
    stopping: Boolean,
    startupTaskManager: StartupTaskManager,
    onRunNow: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRunning = runtime?.status == StartupTaskRunStatus.RUNNING
    var log by remember(task.id) { mutableStateOf<BackgroundShellLogTail?>(null) }

    LaunchedEffect(task.id, isRunning, runtime?.endedAt) {
        while (true) {
            log = startupTaskManager.readLogTail(task.id)
            if (!isRunning) break
            delay(LOG_POLL_INTERVAL_MS)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = task.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = runtime.statusSummary(now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    runtime?.lastAutoRunAt?.let { autoRunAt ->
                        Text(
                            text = stringResource(
                                R.string.setting_startup_tasks_last_auto_run,
                                formatTimestamp(autoRunAt),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isRunning) {
                    FilledTonalButton(onClick = onStop, enabled = !stopping) {
                        Text(stringResource(if (stopping) R.string.background_tasks_stopping else R.string.stop))
                    }
                } else {
                    FilledTonalButton(onClick = onRunNow) {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.setting_startup_tasks_run_now))
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val commandMaxHeight = maxHeight * 0.4f
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = commandMaxHeight)
                            .clip(MaterialTheme.shapes.large)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        HighlightCodeBlock(
                            code = task.command,
                            language = "bash",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        text = stringResource(R.string.setting_startup_tasks_log_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState(), reverseScrolling = true)
                                .padding(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (log?.truncated == true && !runtime?.logPath.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.background_tasks_log_truncated,
                                            runtime?.logPath.orEmpty(),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val text = log?.text.orEmpty()
                                if (text.isEmpty()) {
                                    Text(
                                        text = stringResource(
                                            if (isRunning) {
                                                R.string.background_tasks_no_output_yet
                                            } else {
                                                R.string.background_tasks_no_output
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    SelectionContainer {
                                        Text(
                                            text = text,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
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
}

private fun StartupTask.displayName(): String =
    name.trim().ifBlank {
        command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32).orEmpty().ifBlank { "Task" }
    }

private fun StartupTaskTrigger.icon(): ImageVector = when (this) {
    StartupTaskTrigger.EVERY_LAUNCH -> HugeIcons.Repeat
    StartupTaskTrigger.DAILY_FIRST_LAUNCH -> HugeIcons.Clock01
    StartupTaskTrigger.WEEKLY_FIRST_LAUNCH -> HugeIcons.Calendar03
    StartupTaskTrigger.ONCE -> HugeIcons.Zap
}

@Composable
private fun StartupTaskTrigger.shortLabel(): String = when (this) {
    StartupTaskTrigger.EVERY_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_every_launch)
    StartupTaskTrigger.DAILY_FIRST_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_daily_first)
    StartupTaskTrigger.WEEKLY_FIRST_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_weekly_first)
    StartupTaskTrigger.ONCE -> stringResource(R.string.setting_startup_tasks_trigger_once)
}

@Composable
private fun StartupTaskTrigger.description(): String = when (this) {
    StartupTaskTrigger.EVERY_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_every_launch_desc)
    StartupTaskTrigger.DAILY_FIRST_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_daily_first_desc)
    StartupTaskTrigger.WEEKLY_FIRST_LAUNCH -> stringResource(R.string.setting_startup_tasks_trigger_weekly_first_desc)
    StartupTaskTrigger.ONCE -> stringResource(R.string.setting_startup_tasks_trigger_once_desc)
}

@Composable
private fun StartupTaskRuntimeState?.statusSummary(now: Long): String {
    if (this == null || status == StartupTaskRunStatus.IDLE) {
        return stringResource(R.string.setting_startup_tasks_status_idle)
    }
    val start = startedAt ?: now
    val duration = formatStartupDuration((endedAt ?: now) - start)
    return when (status) {
        StartupTaskRunStatus.IDLE -> stringResource(R.string.setting_startup_tasks_status_idle)
        StartupTaskRunStatus.RUNNING -> stringResource(R.string.background_tasks_status_running, duration)
        StartupTaskRunStatus.EXITED -> stringResource(
            R.string.background_tasks_status_exited,
            exitCode ?: -1,
            duration,
        )
        StartupTaskRunStatus.KILLED -> stringResource(R.string.background_tasks_status_stopped, duration)
        StartupTaskRunStatus.FAILED -> stringResource(
            R.string.setting_startup_tasks_status_failed,
            error.orEmpty(),
        )
    }
}

@Composable
private fun formatStartupDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0).toInt()
    return when {
        seconds < 60 -> stringResource(R.string.background_tasks_duration_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.background_tasks_duration_minutes, seconds / 60, seconds % 60)
        else -> stringResource(R.string.background_tasks_duration_hours, seconds / 3600, seconds % 3600 / 60)
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(AUTO_RUN_TIME_FORMATTER)
