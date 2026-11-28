package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.StartupTask
import me.rerere.rikkahub.data.datastore.StartupTaskTrigger
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

private const val TAG = "StartupTaskManager"
private const val STATE_FILE_NAME = "startup_tasks_state.json"
private const val LOG_TAIL_BYTES = 32 * 1024L
private const val PERSISTED_LOG_MAX_CHARS = 8 * 1024
private const val KILL_EXIT_WAIT_MS = 5_000L
private const val SSH_COMMAND_TIMEOUT_MS = 300_000L

enum class StartupTaskRunStatus {
    IDLE,
    RUNNING,
    EXITED,
    KILLED,
    FAILED,
}

data class StartupTaskRuntimeState(
    val taskId: String,
    val status: StartupTaskRunStatus = StartupTaskRunStatus.IDLE,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val logPath: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val lastAutoRunAt: Long? = null,
    val lastAutoRunDate: String? = null,
    val lastAutoRunWeek: String? = null,
)

@Serializable
internal data class StartupTaskScheduleRecord(
    val lastAutoRunAt: Long? = null,
    val lastAutoRunDate: String? = null,
    val lastAutoRunWeek: String? = null,
)

@Serializable
private data class StartupTaskPersistedRecord(
    val lastAutoRunAt: Long? = null,
    val lastAutoRunDate: String? = null,
    val lastAutoRunWeek: String? = null,
    val lastRunAt: Long? = null,
    val lastEndedAt: Long? = null,
    val lastExitCode: Int? = null,
    val lastKilled: Boolean = false,
    val lastError: String? = null,
    val lastWorkspaceId: String? = null,
    val lastWorkspaceName: String? = null,
    val lastOutputTail: String? = null,
    val lastOutputTruncated: Boolean = false,
) {
    fun toScheduleRecord(): StartupTaskScheduleRecord = StartupTaskScheduleRecord(
        lastAutoRunAt = lastAutoRunAt,
        lastAutoRunDate = lastAutoRunDate,
        lastAutoRunWeek = lastAutoRunWeek,
    )
}

@Serializable
private data class StartupTaskStateSnapshot(
    val records: Map<String, StartupTaskPersistedRecord> = emptyMap(),
)

internal fun LocalDate.isoWeekKey(): String {
    val weekYear = get(IsoFields.WEEK_BASED_YEAR)
    val week = get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    return "%04d-W%02d".format(Locale.US, weekYear, week)
}

internal fun shouldAutoRunStartupTask(
    task: StartupTask,
    record: StartupTaskScheduleRecord?,
    nowDate: LocalDate,
    isColdStart: Boolean = true,
): Boolean {
    if (!task.enabled || task.command.isBlank()) return false
    return when (task.trigger) {
        StartupTaskTrigger.EVERY_LAUNCH -> isColdStart
        StartupTaskTrigger.DAILY_FIRST_LAUNCH -> record?.lastAutoRunDate != nowDate.toString()
        StartupTaskTrigger.WEEKLY_FIRST_LAUNCH -> record?.lastAutoRunWeek != nowDate.isoWeekKey()
        StartupTaskTrigger.ONCE -> isColdStart && record?.lastAutoRunAt == null
    }
}

/**
 * 管理应用启动任务的自动调度、手动运行、后台保活与日志查看。
 *
 * 配置存放在 [Settings.startupTasksConfig]，执行记录独立落盘到 `filesDir/startup_tasks_state.json`，
 * 避免每次冷启动改写 Settings 触发全量重组，也避免跨设备恢复备份时带入过期的触发日期。
 */
class StartupTaskManager(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val json: Json,
) {
    private sealed interface ActiveHandle {
        val workspaceId: String
        val exited: CompletableDeferred<Unit>
        var killing: Boolean

        class Local(
            val runId: String,
            val fgsId: String,
            override val workspaceId: String,
            val command: WorkspaceBackgroundCommand,
        ) : ActiveHandle {
            override val exited = CompletableDeferred<Unit>()

            @Volatile
            override var killing: Boolean = false
        }

        class Ssh(
            override val workspaceId: String,
            val job: Job,
        ) : ActiveHandle {
            override val exited = CompletableDeferred<Unit>()

            @Volatile
            override var killing: Boolean = false
        }
    }

    private val stateFile: File
        get() = File(context.filesDir, STATE_FILE_NAME)

    private val loadMutex = Mutex()
    private val persistMutex = Mutex()
    private val scheduleMutex = Mutex()

    @Volatile
    private var stateLoaded = false

    private val coldStartTriggered = AtomicBoolean(false)

    @Volatile
    private var lastCheckedDate: LocalDate? = null

    private val startingIds = ConcurrentHashMap.newKeySet<String>()
    private val runningHandles = ConcurrentHashMap<String, ActiveHandle>()
    private val lastLocalCommands = ConcurrentHashMap<String, WorkspaceBackgroundCommand>()
    private val persistedRecords = ConcurrentHashMap<String, StartupTaskPersistedRecord>()

    private val _states = MutableStateFlow<Map<String, StartupTaskRuntimeState>>(emptyMap())
    val states: StateFlow<Map<String, StartupTaskRuntimeState>> = _states.asStateFlow()

    init {
        appScope.launch(Dispatchers.IO) {
            ensureStateLoaded()
        }
    }

    /** 应用冷启动完成工作区清理与完整性检查且进入前台后调用，每个进程最多执行一次 */
    suspend fun runOnAppStartup() {
        if (!coldStartTriggered.compareAndSet(false, true)) return
        ensureStateLoaded()
        val nowDate = LocalDate.now()
        scheduleMutex.withLock {
            lastCheckedDate = nowDate
            val config = settingsStore.awaitLoaded().startupTasksConfig
            val eligible = config.tasks.filter { task ->
                shouldAutoRunStartupTask(
                    task = task,
                    record = persistedRecords[task.id]?.toScheduleRecord(),
                    nowDate = nowDate,
                    isColdStart = true,
                )
            }
            if (eligible.isEmpty()) return@withLock
            Log.i(TAG, "runOnAppStartup: launching ${eligible.size} task(s)")
            coroutineScope {
                eligible.forEach { task ->
                    launch {
                        launchTask(
                            task = task,
                            defaultWorkspaceId = config.defaultWorkspaceId,
                            isAutoRun = true,
                            autoRunDate = nowDate,
                        )
                    }
                }
            }
        }
    }

    /** 应用在常驻后台跨天/跨周后重新回到前台时，补触发每日/每周首次启动任务 */
    fun onAppForeground() {
        if (!coldStartTriggered.get()) return
        if (CrashHandler.hasCrashed(context)) return
        val today = LocalDate.now()
        if (today == lastCheckedDate) return
        appScope.launch(Dispatchers.IO) {
            ensureStateLoaded()
            scheduleMutex.withLock {
                if (today == lastCheckedDate) return@withLock
                lastCheckedDate = today
                val config = settingsStore.awaitLoaded().startupTasksConfig
                val eligible = config.tasks.filter { task ->
                    shouldAutoRunStartupTask(
                        task = task,
                        record = persistedRecords[task.id]?.toScheduleRecord(),
                        nowDate = today,
                        isColdStart = false,
                    )
                }
                if (eligible.isEmpty()) return@withLock
                Log.i(TAG, "onAppForeground rollover ($today): launching ${eligible.size} task(s)")
                coroutineScope {
                    eligible.forEach { task ->
                        launch {
                            launchTask(
                                task = task,
                                defaultWorkspaceId = config.defaultWorkspaceId,
                                isAutoRun = true,
                                autoRunDate = today,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 手动立即运行任务，不消耗自动触发次数 */
    suspend fun runNow(task: StartupTask, defaultWorkspaceId: String?): Result<Unit> {
        ensureStateLoaded()
        return launchTask(
            task = task,
            defaultWorkspaceId = defaultWorkspaceId,
            isAutoRun = false,
            autoRunDate = LocalDate.now(),
        )
    }

    /** 停止正在运行的任务 */
    suspend fun stop(taskId: String) {
        val handle = runningHandles[taskId] ?: return
        terminate(handle)
        withTimeoutOrNull(KILL_EXIT_WAIT_MS) { handle.exited.await() }
    }

    /** 删除工作区或重装 Rootfs 前终止该工作区内正在运行的启动任务 */
    suspend fun killWorkspace(workspaceId: String) {
        val matched = runningHandles.entries
            .filter { it.value.workspaceId == workspaceId }
            .map { it.value }
        if (matched.isEmpty()) return
        coroutineScope {
            matched.forEach { handle ->
                launch {
                    terminate(handle)
                    withTimeoutOrNull(KILL_EXIT_WAIT_MS) { handle.exited.await() }
                }
            }
        }
    }

    /** 清除自动触发记录，让「每天首次 / 每周首次 / 仅一次」在下次启动重新生效 */
    suspend fun resetAutoRunRecord(taskId: String) {
        ensureStateLoaded()
        persistedRecords.computeIfPresent(taskId) { _, record ->
            record.copy(
                lastAutoRunAt = null,
                lastAutoRunDate = null,
                lastAutoRunWeek = null,
            )
        }
        _states.update { map ->
            val current = map[taskId] ?: return@update map
            map + (
                taskId to current.copy(
                    lastAutoRunAt = null,
                    lastAutoRunDate = null,
                    lastAutoRunWeek = null,
                )
            )
        }
        persistState()
    }

    /** 删除任务时停止进程并清理本地状态记录 */
    suspend fun removeTaskState(taskId: String) {
        stop(taskId)
        lastLocalCommands.remove(taskId)
        persistedRecords.remove(taskId)
        _states.update { it - taskId }
        persistState()
    }

    /** 读取任务日志末尾（运行中实时读日志文件，否则回退上次落盘的日志片段） */
    suspend fun readLogTail(taskId: String): BackgroundShellLogTail? {
        ensureStateLoaded()
        val activeLocal = runningHandles[taskId] as? ActiveHandle.Local
        val localCommand = activeLocal?.command ?: lastLocalCommands[taskId]
        if (localCommand != null) {
            val tail = withContext(Dispatchers.IO) {
                runCatching {
                    val size = localCommand.outputSize()
                    val text = readTailLines(localCommand, LOG_TAIL_BYTES).joinToString("\n")
                    BackgroundShellLogTail(
                        text = text,
                        truncated = size > LOG_TAIL_BYTES,
                    )
                }.getOrNull()
            }
            if (tail != null && (activeLocal != null || tail.text.isNotEmpty())) {
                return tail
            }
        }
        val persisted = persistedRecords[taskId] ?: return null
        val savedText = persisted.lastOutputTail ?: return null
        return BackgroundShellLogTail(
            text = savedText,
            truncated = persisted.lastOutputTruncated,
        )
    }

    private suspend fun launchTask(
        task: StartupTask,
        defaultWorkspaceId: String?,
        isAutoRun: Boolean,
        autoRunDate: LocalDate,
    ): Result<Unit> {
        if (runningHandles.containsKey(task.id) || !startingIds.add(task.id)) {
            return Result.failure(
                IllegalStateException(context.getString(R.string.setting_startup_tasks_error_already_running))
            )
        }
        return try {
            val commandText = task.command.trim()
            if (commandText.isBlank()) {
                val msg = context.getString(R.string.setting_startup_tasks_error_empty_command)
                recordLaunchFailure(task.id, null, null, msg)
                return Result.failure(IllegalArgumentException(msg))
            }
            val targetWorkspaceId = task.workspaceId?.takeIf { it.isNotBlank() }
                ?: defaultWorkspaceId?.takeIf { it.isNotBlank() }
            if (targetWorkspaceId == null) {
                val msg = context.getString(R.string.setting_startup_tasks_error_no_workspace)
                recordLaunchFailure(task.id, null, null, msg)
                return Result.failure(IllegalStateException(msg))
            }
            val workspace = workspaceRepository.getById(targetWorkspaceId)
            if (workspace == null) {
                val msg = context.getString(R.string.setting_startup_tasks_error_workspace_missing)
                recordLaunchFailure(task.id, targetWorkspaceId, null, msg)
                return Result.failure(IllegalStateException(msg))
            }
            val resolvedCwd = workspaceRepository.layout(workspace.id)
                ?.relativeToWorkspace(task.cwd.trim())
                ?.trim('/')
                ?: task.cwd.trim().trim('/')
            if (workspace.capabilities().backgroundShell) {
                if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
                    val msg = context.getString(R.string.setting_startup_tasks_error_rootfs_not_ready)
                    recordLaunchFailure(task.id, workspace.id, workspace.name, msg)
                    return Result.failure(IllegalStateException(msg))
                }
                startLocalBackgroundTask(
                    task = task,
                    commandText = commandText,
                    cwd = resolvedCwd,
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    isAutoRun = isAutoRun,
                    autoRunDate = autoRunDate,
                )
            } else {
                startSshTask(
                    task = task,
                    commandText = commandText,
                    cwd = resolvedCwd,
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    isAutoRun = isAutoRun,
                    autoRunDate = autoRunDate,
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "launchTask failed: task=${task.id}", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            recordLaunchFailure(task.id, task.workspaceId ?: defaultWorkspaceId, null, msg)
            Result.failure(e)
        } finally {
            startingIds.remove(task.id)
        }
    }

    private suspend fun startLocalBackgroundTask(
        task: StartupTask,
        commandText: String,
        cwd: String,
        workspaceId: String,
        workspaceName: String,
        isAutoRun: Boolean,
        autoRunDate: LocalDate,
    ) {
        val safePrefix = task.id.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "task" }
        val runId = "startup_${safePrefix}_${Uuid.random().toHexString().take(6)}"
        val fgsId = "startup:$runId"
        val backgroundCommand = workspaceRepository.startBackgroundCommand(
            id = workspaceId,
            command = commandText,
            cwd = cwd,
            taskId = runId,
        )
        val startedAt = System.currentTimeMillis()
        val handle = ActiveHandle.Local(
            runId = runId,
            fgsId = fgsId,
            workspaceId = workspaceId,
            command = backgroundCommand,
        )
        runningHandles[task.id] = handle
        lastLocalCommands[task.id] = backgroundCommand

        val autoRecord = markStartedRecord(
            taskId = task.id,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            startedAt = startedAt,
            isAutoRun = isAutoRun,
            autoRunDate = autoRunDate,
        )
        _states.update { map ->
            map + (
                task.id to StartupTaskRuntimeState(
                    taskId = task.id,
                    status = StartupTaskRunStatus.RUNNING,
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    logPath = backgroundCommand.logPath,
                    startedAt = startedAt,
                    endedAt = null,
                    exitCode = null,
                    error = null,
                    lastAutoRunAt = autoRecord.lastAutoRunAt,
                    lastAutoRunDate = autoRecord.lastAutoRunDate,
                    lastAutoRunWeek = autoRecord.lastAutoRunWeek,
                )
            )
        }
        BackgroundShellForegroundService.acquire(context, fgsId, conversationId = null)
        thread(name = "startup-task-$safePrefix", isDaemon = true) {
            val exitCode = try {
                backgroundCommand.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "waitFor failed: task=${task.id}, runId=$runId", e)
                -1
            }
            onLocalExit(task.id, handle, exitCode)
        }
        Log.i(TAG, "Started local startup task: task=${task.id}, workspace=$workspaceId, runId=$runId")
    }

    private suspend fun startSshTask(
        task: StartupTask,
        commandText: String,
        cwd: String,
        workspaceId: String,
        workspaceName: String,
        isAutoRun: Boolean,
        autoRunDate: LocalDate,
    ) {
        val startedAt = System.currentTimeMillis()
        lateinit var handle: ActiveHandle.Ssh
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val result = workspaceRepository.executeCommand(
                    id = workspaceId,
                    command = commandText,
                    cwd = cwd,
                    timeoutMillis = SSH_COMMAND_TIMEOUT_MS,
                )
                val combinedOutput = buildString {
                    if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(result.stderr.trimEnd())
                    }
                }
                val endedAt = System.currentTimeMillis()
                val killed = handle.killing
                val tail = combinedOutput.takeLast(PERSISTED_LOG_MAX_CHARS)
                val truncated = result.truncated || combinedOutput.length > PERSISTED_LOG_MAX_CHARS
                val errorText = if (result.timedOut) "Command timed out" else null
                finishTaskRecord(
                    taskId = task.id,
                    status = if (killed) StartupTaskRunStatus.KILLED else StartupTaskRunStatus.EXITED,
                    exitCode = result.exitCode,
                    endedAt = endedAt,
                    error = errorText,
                    outputTail = tail,
                    outputTruncated = truncated,
                )
            } catch (e: CancellationException) {
                val endedAt = System.currentTimeMillis()
                finishTaskRecord(
                    taskId = task.id,
                    status = StartupTaskRunStatus.KILLED,
                    exitCode = -1,
                    endedAt = endedAt,
                    error = null,
                    outputTail = null,
                    outputTruncated = false,
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SSH startup task failed: task=${task.id}", e)
                val endedAt = System.currentTimeMillis()
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                finishTaskRecord(
                    taskId = task.id,
                    status = StartupTaskRunStatus.FAILED,
                    exitCode = null,
                    endedAt = endedAt,
                    error = msg,
                    outputTail = msg,
                    outputTruncated = false,
                )
            } finally {
                runningHandles.remove(task.id, handle)
                handle.exited.complete(Unit)
            }
        }
        handle = ActiveHandle.Ssh(workspaceId = workspaceId, job = job)
        runningHandles[task.id] = handle
        lastLocalCommands.remove(task.id)

        val autoRecord = markStartedRecord(
            taskId = task.id,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            startedAt = startedAt,
            isAutoRun = isAutoRun,
            autoRunDate = autoRunDate,
        )
        _states.update { map ->
            map + (
                task.id to StartupTaskRuntimeState(
                    taskId = task.id,
                    status = StartupTaskRunStatus.RUNNING,
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    logPath = null,
                    startedAt = startedAt,
                    endedAt = null,
                    exitCode = null,
                    error = null,
                    lastAutoRunAt = autoRecord.lastAutoRunAt,
                    lastAutoRunDate = autoRecord.lastAutoRunDate,
                    lastAutoRunWeek = autoRecord.lastAutoRunWeek,
                )
            )
        }
        job.start()
        Log.i(TAG, "Started SSH startup task: task=${task.id}, workspace=$workspaceId")
    }

    private fun onLocalExit(taskId: String, handle: ActiveHandle.Local, exitCode: Int) {
        val killed = handle.killing
        val endedAt = System.currentTimeMillis()
        val tail = runCatching {
            readTailLines(handle.command, LOG_TAIL_BYTES).joinToString("\n")
        }.getOrDefault("")
        val truncated = runCatching {
            handle.command.outputSize() > LOG_TAIL_BYTES || tail.length > PERSISTED_LOG_MAX_CHARS
        }.getOrDefault(false)
        val savedTail = tail.takeLast(PERSISTED_LOG_MAX_CHARS)

        val status = if (killed) StartupTaskRunStatus.KILLED else StartupTaskRunStatus.EXITED
        val updated = persistedRecords.compute(taskId) { _, prev ->
            (prev ?: StartupTaskPersistedRecord()).copy(
                lastEndedAt = endedAt,
                lastExitCode = exitCode,
                lastKilled = killed,
                lastError = null,
                lastOutputTail = savedTail,
                lastOutputTruncated = truncated,
            )
        }
        _states.update { map ->
            val current = map[taskId] ?: StartupTaskRuntimeState(taskId = taskId)
            map + (
                taskId to current.copy(
                    status = status,
                    exitCode = exitCode,
                    endedAt = endedAt,
                    error = null,
                    lastAutoRunAt = updated?.lastAutoRunAt ?: current.lastAutoRunAt,
                    lastAutoRunDate = updated?.lastAutoRunDate ?: current.lastAutoRunDate,
                    lastAutoRunWeek = updated?.lastAutoRunWeek ?: current.lastAutoRunWeek,
                )
            )
        }
        runningHandles.remove(taskId, handle)
        handle.exited.complete(Unit)
        BackgroundShellForegroundService.release(context, handle.fgsId)
        appScope.launch(Dispatchers.IO) {
            persistState()
        }
        Log.i(TAG, "Local startup task exited: task=$taskId, code=$exitCode, killed=$killed")
    }

    private suspend fun finishTaskRecord(
        taskId: String,
        status: StartupTaskRunStatus,
        exitCode: Int?,
        endedAt: Long,
        error: String?,
        outputTail: String?,
        outputTruncated: Boolean,
    ) {
        val updated = persistedRecords.compute(taskId) { _, prev ->
            (prev ?: StartupTaskPersistedRecord()).copy(
                lastEndedAt = endedAt,
                lastExitCode = exitCode,
                lastKilled = status == StartupTaskRunStatus.KILLED,
                lastError = error,
                lastOutputTail = outputTail ?: prev?.lastOutputTail,
                lastOutputTruncated = if (outputTail != null) outputTruncated else (prev?.lastOutputTruncated ?: false),
            )
        }
        _states.update { map ->
            val current = map[taskId] ?: StartupTaskRuntimeState(taskId = taskId)
            map + (
                taskId to current.copy(
                    status = status,
                    exitCode = exitCode,
                    endedAt = endedAt,
                    error = error,
                    lastAutoRunAt = updated?.lastAutoRunAt ?: current.lastAutoRunAt,
                    lastAutoRunDate = updated?.lastAutoRunDate ?: current.lastAutoRunDate,
                    lastAutoRunWeek = updated?.lastAutoRunWeek ?: current.lastAutoRunWeek,
                )
            )
        }
        withContext(NonCancellable + Dispatchers.IO) {
            persistState()
        }
    }

    private suspend fun markStartedRecord(
        taskId: String,
        workspaceId: String,
        workspaceName: String,
        startedAt: Long,
        isAutoRun: Boolean,
        autoRunDate: LocalDate,
    ): StartupTaskPersistedRecord {
        val updated = persistedRecords.compute(taskId) { _, prev ->
            val base = prev ?: StartupTaskPersistedRecord()
            base.copy(
                lastAutoRunAt = if (isAutoRun) startedAt else base.lastAutoRunAt,
                lastAutoRunDate = if (isAutoRun) autoRunDate.toString() else base.lastAutoRunDate,
                lastAutoRunWeek = if (isAutoRun) autoRunDate.isoWeekKey() else base.lastAutoRunWeek,
                lastRunAt = startedAt,
                lastEndedAt = null,
                lastExitCode = null,
                lastKilled = false,
                lastError = null,
                lastWorkspaceId = workspaceId,
                lastWorkspaceName = workspaceName,
            )
        }!!
        persistState()
        return updated
    }

    private suspend fun recordLaunchFailure(
        taskId: String,
        workspaceId: String?,
        workspaceName: String?,
        errorMessage: String,
    ) {
        val now = System.currentTimeMillis()
        val updated = persistedRecords.compute(taskId) { _, prev ->
            (prev ?: StartupTaskPersistedRecord()).copy(
                lastRunAt = now,
                lastEndedAt = now,
                lastExitCode = null,
                lastKilled = false,
                lastError = errorMessage,
                lastWorkspaceId = workspaceId,
                lastWorkspaceName = workspaceName,
                lastOutputTail = errorMessage,
                lastOutputTruncated = false,
            )
        }
        _states.update { map ->
            val current = map[taskId] ?: StartupTaskRuntimeState(taskId = taskId)
            map + (
                taskId to current.copy(
                    status = StartupTaskRunStatus.FAILED,
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    startedAt = now,
                    endedAt = now,
                    exitCode = null,
                    error = errorMessage,
                    lastAutoRunAt = updated?.lastAutoRunAt ?: current.lastAutoRunAt,
                    lastAutoRunDate = updated?.lastAutoRunDate ?: current.lastAutoRunDate,
                    lastAutoRunWeek = updated?.lastAutoRunWeek ?: current.lastAutoRunWeek,
                )
            )
        }
        persistState()
    }

    private suspend fun terminate(handle: ActiveHandle) {
        if (handle.exited.isCompleted) return
        handle.killing = true
        when (handle) {
            is ActiveHandle.Local -> {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { handle.command.kill() }.onFailure { Log.e(TAG, "kill local task failed", it) }
                }
            }

            is ActiveHandle.Ssh -> {
                handle.job.cancel()
            }
        }
    }

    private suspend fun ensureStateLoaded() {
        if (stateLoaded) return
        loadMutex.withLock {
            if (stateLoaded) return
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val file = stateFile
                    if (!file.isFile) return@runCatching emptyMap<String, StartupTaskPersistedRecord>()
                    json.decodeFromString<StartupTaskStateSnapshot>(file.readText()).records
                }.onFailure {
                    Log.w(TAG, "Failed to read startup task state", it)
                }.getOrDefault(emptyMap())
            }
            persistedRecords.putAll(loaded)
            _states.update { current ->
                val merged = LinkedHashMap<String, StartupTaskRuntimeState>(loaded.size + current.size)
                loaded.forEach { (taskId, record) ->
                    merged[taskId] = record.toIdleRuntimeState(taskId)
                }
                // 已经进入运行中的任务以实时状态为准
                merged.putAll(current)
                merged
            }
            stateLoaded = true
        }
    }

    private fun StartupTaskPersistedRecord.toIdleRuntimeState(taskId: String): StartupTaskRuntimeState {
        val status = when {
            lastError != null -> StartupTaskRunStatus.FAILED
            lastKilled -> StartupTaskRunStatus.KILLED
            lastExitCode != null -> StartupTaskRunStatus.EXITED
            else -> StartupTaskRunStatus.IDLE
        }
        return StartupTaskRuntimeState(
            taskId = taskId,
            status = status,
            workspaceId = lastWorkspaceId,
            workspaceName = lastWorkspaceName,
            logPath = null,
            startedAt = lastRunAt,
            endedAt = lastEndedAt,
            exitCode = lastExitCode,
            error = lastError,
            lastAutoRunAt = lastAutoRunAt,
            lastAutoRunDate = lastAutoRunDate,
            lastAutoRunWeek = lastAutoRunWeek,
        )
    }

    private suspend fun persistState() = withContext(NonCancellable + Dispatchers.IO) {
        persistMutex.withLock {
            runCatching {
                val snapshot = StartupTaskStateSnapshot(records = persistedRecords.toMap())
                val target = stateFile
                val tmp = File(target.parentFile, "$STATE_FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                if (!tmp.renameTo(target)) {
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            }.onFailure {
                Log.w(TAG, "Failed to persist startup task state", it)
            }
        }
    }
}
