package com.mangashelf.reader.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * 权限面板（v1.6.0）。
 *
 * 设计前提：**任何权限都不在冷启动时申请**。
 * - 选图片默认走 Photo Picker，本身不需要权限
 * - 保存到相册走 MediaStore，Android 10+ 不需要写权限
 * 所以这里的权限只在"浏览全部相册"这类可选能力上才需要，用户主动点才申请。
 */
@Composable
fun PermissionSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 手动触发重组：授权/拒绝后系统不会通知 Compose
    var tick by remember { mutableIntStateOf(0) }

    val notifyPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    val mediaPermission = when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
        Build.VERSION.SDK_INT >= 29 -> null // 10~12 走 MediaStore，不需要读权限
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun granted(permission: String?): Boolean {
        if (permission == null) return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    val notifyGranted = remember(tick) { granted(notifyPermission) }
    val mediaGranted = remember(tick) { granted(mediaPermission) }

    val requester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("权限", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "导入、导出、看漫画都不需要任何权限：选文件用系统选择器，存相册用 MediaStore。下面两项是可选的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            PermissionRow(
                title = "通知",
                detail = if (notifyPermission == null) {
                    "当前系统版本不需要"
                } else if (notifyGranted) {
                    "已允许 · 导入完成/失败会提示"
                } else {
                    "未允许 · 导入完成时不会提示（不影响导入本身）"
                },
                actionLabel = if (!notifyGranted && notifyPermission != null) "去允许" else null,
                onAction = { notifyPermission?.let { requester.launch(it) } }
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                title = "读取相册",
                detail = if (mediaPermission == null) {
                    "当前系统版本不需要（走系统相册选择器）"
                } else if (mediaGranted) {
                    "已允许"
                } else {
                    "未允许 · 选图仍可用系统相册选择器，不需要它"
                },
                actionLabel = null,
                onAction = {}
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { openAppSettings(context) }) { Text("去系统设置里管理权限") }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
