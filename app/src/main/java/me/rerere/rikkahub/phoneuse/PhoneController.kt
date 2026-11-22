package me.rerere.rikkahub.phoneuse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings

/** 屏幕上一个可以操作或带文字的元素，[index] 在下一次 [PhoneController.captureScreen] 之前有效，坐标是屏幕像素 */
data class PhoneElement(
    val index: Int,
    val className: String,
    val label: String,
    /** 布局里的资源 ID 名（如 btn_send），应用没有设置或被系统隐藏时为空 */
    val resourceId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
)

data class PhoneScreen(
    val packageName: String?,
    val appLabel: String?,
    /** 这份元素来自哪些应用的窗口，[packageName] 只是最上层的那个 */
    val packages: Set<String>,
    val width: Int,
    val height: Int,
    val keyboardVisible: Boolean,
    val elements: List<PhoneElement>,
    /** 元素太多时只保留前面的一部分 */
    val truncated: Boolean,
)

data class PhoneApp(val packageName: String, val label: String)

enum class PhoneKey { BACK, HOME, RECENTS, NOTIFICATIONS, ENTER }

/** Phone Use 的执行方式，用户在设置里选 */
enum class PhoneUseBackend {
    /** 无障碍服务，用户在系统设置里手动开启 */
    ACCESSIBILITY,

    /** Shizuku 给的 shell 权限（adb 级别），不需要开关无障碍 */
    SHIZUKU,

    /** root，su 给的 uid 0 */
    ROOT,
}

/**
 * 操作手机的底层能力，坐标都是屏幕像素。
 * 无障碍服务、Shizuku、root 各有一份实现，用户在设置里挑一个。
 */
interface PhoneController {
    /** 能不能截图：无障碍截图要 Android 11+ 或者用户额外开着录屏，shell 方式一直能截 */
    val canScreenshot: Boolean

    /**
     * 屏幕上所有可见窗口所属的应用，和 [captureScreen] 读取的窗口范围一致，不算本应用自己的悬浮窗。
     * 分屏或下拉通知栏时不止一个；只用来判断屏幕上有谁，不影响元素编号。
     * shell 方式要跑命令问系统，所以是挂起的
     */
    suspend fun visiblePackages(): Set<String>

    /** 读取当前屏幕上的元素，不包含本应用自己的悬浮窗 */
    suspend fun captureScreen(): PhoneScreen

    /** JPEG 截图，长边不超过 [maxSide] */
    suspend fun screenshot(maxSide: Int): ByteArray

    /** 起点和终点相同时是点击，[durationMs] 决定是轻点还是长按 */
    suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean

    /** 把文字填进 [elementIndex] 指向的输入框；为空时填进当前有输入焦点的输入框 */
    suspend fun setText(elementIndex: Int?, text: String): Boolean

    suspend fun pressKey(key: PhoneKey): Boolean

    fun listApps(): List<PhoneApp>

    suspend fun openApp(packageName: String): Boolean
}

/**
 * 录屏取帧，给 Android 11 以下的 [PhoneController.screenshot] 用。
 * 授权只能由用户在界面上触发，实现放在 github 渠道。
 */
interface PhoneScreenCapture {
    fun start(resultCode: Int, data: Intent)

    fun stop()
}

/**
 * 后台悬浮窗是否真的能显示：开关打开并且有悬浮窗权限。
 * Phone Use 要求它开着，用户才看得到 AI 在做什么、随时能停。
 */
fun isFloatingChatWindowOn(context: Context, settings: Settings): Boolean =
    settings.displaySetting.enableFloatingChatWindow && android.provider.Settings.canDrawOverlays(context)

// 服务类只在 github 渠道里，这里按名字找
private const val PHONE_USE_SERVICE_CLASS = "me.rerere.rikkahub.phoneuse.PhoneUseAccessibilityService"

/**
 * 系统无障碍设置里是否勾选了 Phone Use。勾选了不代表服务在运行：
 * ColorOS 等系统在最近任务里划掉应用后会停掉服务，设置里却仍显示开启，也不会再自动绑定，只能让用户关掉重开。
 */
fun isPhoneUseCheckedInSystem(context: Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val target = ComponentName(context.packageName, PHONE_USE_SERVICE_CLASS)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
}

/**
 * 各执行端连上后把自己登记在这里，工具和设置页从这里拿。
 * 无障碍服务由系统拉起后自己登记；Shizuku 和 root 由 [refreshShells] / [controllerFor] 探测后登记。
 */
class PhoneUseManager(
    /** Shizuku / root 执行端要用到包管理器和剪贴板，单测里可以不传 */
    private val context: Context? = null,
) {
    companion object {
        /** Play 版没有无障碍服务。截图和回车键要用 Android 11 的接口，
         * 更低的版本只能读元素列表，截图要用户额外开录屏 */
        val isSupported: Boolean
            get() = !BuildConfig.IS_PLAY_STORE
    }

    private val _controllers = MutableStateFlow<Map<PhoneUseBackend, PhoneController>>(emptyMap())

    /** 现在能用的执行端，键是方式 */
    val controllers: StateFlow<Map<PhoneUseBackend, PhoneController>> = _controllers.asStateFlow()

    /** 录屏取帧的入口，无障碍服务连上后填进来；服务没开时为空 */
    @Volatile
    var screenCapture: PhoneScreenCapture? = null

    private val _screenCaptureActive = MutableStateFlow(false)
    val screenCaptureActive: StateFlow<Boolean> = _screenCaptureActive.asStateFlow()

    fun startScreenCapture(resultCode: Int, data: Intent): Boolean {
        val capture = screenCapture ?: return false
        capture.start(resultCode, data)
        return true
    }

    fun stopScreenCapture() {
        screenCapture?.stop()
    }

    fun setScreenCaptureActive(active: Boolean) {
        _screenCaptureActive.value = active
    }

    private val _acting = MutableStateFlow(false)

    /** 正在截图或执行手势，这期间悬浮窗要让出屏幕：不能出现在截图里，也不能挡住点击 */
    val acting: StateFlow<Boolean> = _acting.asStateFlow()

    private val shizuku: ShellPhoneController? by lazy {
        context?.let { ShellPhoneController(it, ShizukuShell()) }
    }
    private val root: ShellPhoneController? by lazy {
        context?.let { ShellPhoneController(it, RootShell()) }
    }

    fun attach(controller: PhoneController, backend: PhoneUseBackend = PhoneUseBackend.ACCESSIBILITY) {
        _controllers.update { it + (backend to controller) }
    }

    /** 只摘掉登记过的那个实例，避免旧服务的回调把新的顶掉 */
    fun detach(controller: PhoneController) {
        _controllers.update { current -> current.filterValues { it !== controller } }
    }

    fun detach(backend: PhoneUseBackend) {
        _controllers.update { it - backend }
    }

    /**
     * 重新探测 Shizuku 和 root，设置页打开时调一次。
     * [force] 会重新问一次 su，用户刚在 root 管理里点了允许时用得上
     */
    suspend fun refreshShells(force: Boolean = false): Map<PhoneUseBackend, PhoneController> {
        val shizuku = shizuku
        if (shizuku != null && shizuku.isReady) {
            attach(shizuku, PhoneUseBackend.SHIZUKU)
        } else {
            detach(PhoneUseBackend.SHIZUKU)
        }
        val root = root
        if (root != null && (if (force) root.recheck() else root.isReady)) {
            attach(root, PhoneUseBackend.ROOT)
        } else {
            detach(PhoneUseBackend.ROOT)
        }
        return _controllers.value
    }

    /** 取指定方式的执行端；Shizuku / root 还没探测过时补一次，探测要跑命令所以是挂起函数 */
    suspend fun controllerFor(backend: PhoneUseBackend): PhoneController? {
        _controllers.value[backend]?.let { return it }
        val shell = when (backend) {
            PhoneUseBackend.ACCESSIBILITY -> return null
            PhoneUseBackend.SHIZUKU -> shizuku
            PhoneUseBackend.ROOT -> root
        } ?: return null
        return if (shell.isReady) shell.also { attach(it, backend) } else null
    }

    fun setActing(acting: Boolean) {
        _acting.value = acting
    }
}

