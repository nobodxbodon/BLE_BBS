package com.wuxuan.blemvp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wuxuan.blemvp.ble.蓝牙引擎
import com.wuxuan.blemvp.model.猜拳手势
import com.wuxuan.blemvp.model.猜拳日志
import com.wuxuan.blemvp.model.猜拳界面状态
import com.wuxuan.blemvp.model.猜拳结果
import com.wuxuan.blemvp.storage.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wuxuan.blemvp.storage.PostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private lateinit var 本引擎: 蓝牙引擎
    private val 蓝牙状态流 = MutableStateFlow("BLE: starting…")

    private val 所需权限: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // BLUETOOTH_SCAN is declared with neverForLocation in the manifest,
            // so ACCESS_FINE_LOCATION is not required on Android 12+.
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(已保存状态: Bundle?) {
        super.onCreate(已保存状态)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        本引擎 = 蓝牙引擎(this)
        本引擎.设置生命周期监听器 { 状态, 详情 ->
            蓝牙状态流.value = "[$状态] $详情"
        }
        val 数据库 = AppDatabase.getInstance(this)
        按需请求蓝牙权限()

        val 帖子流 = 数据库.postDao().getAllLatestFirstFlow()

        // Start BLE immediately if permissions are already granted (e.g. re-launch after first run).
        // On first install, 启动 is deferred to onRequestPermissionsResult.
        if (已有全部权限()) 本引擎.启动()

        setContent {
            MaterialTheme {
                val 蓝牙状态 by 蓝牙状态流.collectAsState()
                val 猜拳状态 by 本引擎.猜拳状态流.collectAsState()
                猜拳界面(
                    蓝牙状态 = 蓝牙状态,
                    猜拳状态 = 猜拳状态,
                    选择手势 = { 本引擎.选择猜拳手势(it) },
                    重新开始 = { 本引擎.重新开始猜拳() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart scan + advertising every time the user brings the app to the foreground.
        if (::本引擎.isInitialized) 本引擎.重启扫描()
    }

    override fun onDestroy() {
        本引擎.设置生命周期监听器(null)
        本引擎.停止()
        本引擎.关闭()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 蓝牙权限请求码 && 已有全部权限()) {
            本引擎.启动()
        }
    }

    private fun 已有全部权限() = 所需权限.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun 按需请求蓝牙权限() {
        val 缺失权限 = 所需权限.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (缺失权限.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, 缺失权限.toTypedArray(), 蓝牙权限请求码)
        }
    }

    companion object {
        private const val 蓝牙权限请求码 = 1001
    }
}

private const val SHOW_DEBUG_ROW = false

@Composable
private fun 猜拳界面(
    蓝牙状态: String,
    猜拳状态: 猜拳界面状态,
    选择手势: (猜拳手势) -> Unit,
    重新开始: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.rps_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.rps_connection_status, 蓝牙状态),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = 猜拳状态.本方选择?.let {
                stringResource(R.string.rps_local_choice, 手势文字(it))
            } ?: stringResource(R.string.rps_local_choice_empty),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = when {
                猜拳状态.对方选择 != null -> stringResource(
                    R.string.rps_peer_choice,
                    手势文字(猜拳状态.对方选择)
                )
                猜拳状态.对方已出 -> stringResource(R.string.rps_peer_chosen)
                else -> stringResource(R.string.rps_peer_waiting)
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = 猜拳状态.结果?.let { 结果文字(it) }
                ?: stringResource(R.string.rps_result_pending),
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            猜拳手势.entries.forEach { 手势 ->
                Button(
                    onClick = { 选择手势(手势) },
                    enabled = 猜拳状态.本方选择 == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(手势文字(手势))
                }
            }
        }
        OutlinedButton(onClick = 重新开始) {
            Text(stringResource(R.string.rps_restart))
        }
        Text(
            text = stringResource(R.string.rps_log_title),
            style = MaterialTheme.typography.titleSmall
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(猜拳状态.日志) { 日志 ->
                Text(
                    text = 日志文字(日志),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun 手势文字(手势: 猜拳手势): String = when (手势) {
    猜拳手势.石头 -> stringResource(R.string.rps_rock)
    猜拳手势.剪刀 -> stringResource(R.string.rps_scissors)
    猜拳手势.布 -> stringResource(R.string.rps_paper)
}

@Composable
private fun 结果文字(结果: 猜拳结果): String = when (结果) {
    猜拳结果.本方胜 -> stringResource(R.string.rps_result_win)
    猜拳结果.对方胜 -> stringResource(R.string.rps_result_lose)
    猜拳结果.平局 -> stringResource(R.string.rps_result_draw)
}

@Composable
private fun 日志文字(日志: 猜拳日志): String = when (日志) {
    猜拳日志.本方已出 -> stringResource(R.string.rps_log_local_committed)
    猜拳日志.对方已出 -> stringResource(R.string.rps_log_peer_committed)
    猜拳日志.本方已公开 -> stringResource(R.string.rps_log_local_revealed)
    猜拳日志.对方已公开 -> stringResource(R.string.rps_log_peer_revealed)
    猜拳日志.对方重新开始 -> stringResource(R.string.rps_log_peer_reset)
    猜拳日志.校验失败 -> stringResource(R.string.rps_log_verify_failed)
    猜拳日志.重新开始 -> stringResource(R.string.rps_log_reset)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun 帖子流界面(
    帖子流: Flow<List<PostEntity>>,
    蓝牙状态: String,
    输入文本: String,
    输入变化: (String) -> Unit,
    强制同步: () -> Unit,
    发帖: () -> Unit
) {
    val posts by 帖子流.collectAsState(initial = emptyList())
    val 剪贴板 = LocalClipboardManager.current
    val 上下文 = LocalContext.current
    val 焦点管理器 = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        if (SHOW_DEBUG_ROW) {
            // Debug toggle row. Kept behind a constant switch for development diagnostics.
            var 显示调试信息 by rememberSaveable { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (显示调试信息) {
                    Text(
                        text = 蓝牙状态,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(
                    text = "DBG v0.0.1",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (显示调试信息) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { 显示调试信息 = !显示调试信息 },
                            onLongClick = { 强制同步() }
                        )
                        .padding(4.dp)
                )
            }
        }
        // Post feed — latest on top, right-aligned, full text (no truncation)
        val 列表状态 = rememberLazyListState()
        // Auto-scroll to top whenever the newest 帖子记录 changes (local or received)
        LaunchedEffect(posts.firstOrNull()?.id) {
            if (posts.isNotEmpty()) 列表状态.animateScrollToItem(0)
        }
        LazyColumn(
            state = 列表状态,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            if (posts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_posts),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(posts, key = { it.id }) { 帖子记录 ->
                    Text(
                        text = 帖子记录.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    剪贴板.setText(AnnotatedString(帖子记录.text))
                                    Toast.makeText(
                                        上下文,
                                        上下文.getString(R.string.toast_copied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        // Input area
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = 输入文本,
                onValueChange = 输入变化,
                placeholder = { Text(stringResource(R.string.input_placeholder)) },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4
            )
            Button(
                onClick = {
                    发帖()
                    焦点管理器.clearFocus()
                },
                enabled = 输入文本.trim().isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.action_post))
            }
        }
    }
}
