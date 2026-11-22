package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.phoneuse.PhoneController
import me.rerere.rikkahub.phoneuse.PhoneUseBackend

/**
 * 选 Phone Use 的执行方式。三种方式能做的事一样，区别在于要什么权限：
 * 无障碍要用户在系统设置里开服务，Shizuku 要装 Shizuku 并授权，root 要 su。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneUseBackendSheet(
    selected: PhoneUseBackend,
    controllers: Map<PhoneUseBackend, PhoneController>,
    shizukuInstalled: Boolean,
    accessibilityStopped: Boolean,
    onSelect: (PhoneUseBackend) -> Unit,
    onOpenAccessibility: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRecheck: () -> Unit,
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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_backend),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_backend_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PhoneUseBackend.entries.forEach { backend ->
                val available = controllers.containsKey(backend)
                ListItem(
                    headlineContent = { Text(backendTitle(backend)) },
                    supportingContent = {
                        Column {
                            Text(backendDescription(backend))
                            Text(
                                text = stringResource(
                                    if (available) {
                                        R.string.assistant_page_local_tools_phone_use_backend_ready
                                    } else {
                                        R.string.assistant_page_local_tools_phone_use_backend_not_ready
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (available) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    },
                    leadingContent = {
                        RadioButton(selected = backend == selected, onClick = null)
                    },
                    modifier = Modifier.clickable { onSelect(backend) },
                )
            }
            // 选中的方式不可用时，只给这一步该做的事
            if (!controllers.containsKey(selected)) {
                Button(
                    onClick = when (selected) {
                        PhoneUseBackend.ACCESSIBILITY -> onOpenAccessibility
                        PhoneUseBackend.SHIZUKU -> if (shizukuInstalled) onAuthorizeShizuku else onInstallShizuku
                        PhoneUseBackend.ROOT -> onRecheck
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = when (selected) {
                            PhoneUseBackend.ACCESSIBILITY -> stringResource(
                                if (accessibilityStopped) {
                                    R.string.assistant_page_local_tools_phone_use_restart_service
                                } else {
                                    R.string.assistant_page_local_tools_phone_use_enable_service
                                }
                            )

                            PhoneUseBackend.SHIZUKU -> stringResource(
                                if (shizukuInstalled) {
                                    R.string.assistant_page_local_tools_phone_use_backend_authorize
                                } else {
                                    R.string.assistant_page_local_tools_phone_use_backend_install_shizuku
                                }
                            )

                            PhoneUseBackend.ROOT ->
                                stringResource(R.string.assistant_page_local_tools_phone_use_backend_recheck)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun backendTitle(backend: PhoneUseBackend): String = stringResource(
    when (backend) {
        PhoneUseBackend.ACCESSIBILITY -> R.string.assistant_page_local_tools_phone_use_backend_accessibility
        PhoneUseBackend.SHIZUKU -> R.string.assistant_page_local_tools_phone_use_backend_shizuku
        PhoneUseBackend.ROOT -> R.string.assistant_page_local_tools_phone_use_backend_root
    }
)

@Composable
private fun backendDescription(backend: PhoneUseBackend): String = stringResource(
    when (backend) {
        PhoneUseBackend.ACCESSIBILITY -> R.string.assistant_page_local_tools_phone_use_backend_accessibility_desc
        PhoneUseBackend.SHIZUKU -> R.string.assistant_page_local_tools_phone_use_backend_shizuku_desc
        PhoneUseBackend.ROOT -> R.string.assistant_page_local_tools_phone_use_backend_root_desc
    }
)
