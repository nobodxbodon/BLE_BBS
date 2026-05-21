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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wuxuan.blemvp.ble.蓝牙引擎
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
                var 输入文本 by remember { mutableStateOf("") }
                val 蓝牙状态 by 蓝牙状态流.collectAsState()
                帖子流界面(
                    帖子流 = 帖子流,
                    蓝牙状态 = 蓝牙状态,
                    输入文本 = 输入文本,
                    输入变化 = { 输入文本 = it },
                    强制同步 = { 本引擎.强制同步() },
                    发帖 = {
                        val 待发正文 = 输入文本.trim()
                        if (待发正文.isNotEmpty()) {
                            val (发送数量, 已编码字节) = 本引擎.发送帖子给所有邻机(待发正文)
                            if (发送数量 == 0) {
                                val 快照 = 本引擎.获取邻机快照()
                                if (快照.可写邻机数 > 0) {
                                    lifecycleScope.launch {
                                        delay(400)
                                        本引擎.重试发送给所有邻机(已编码字节)
                                    }
                                }
                            }
                            输入文本 = ""
                        }
                    }
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
        // Debug toggle row
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
                            text = "No posts yet",
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
                                    Toast.makeText(上下文, "Copied", Toast.LENGTH_SHORT).show()
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
                placeholder = { Text("Type a post") },
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
                Text("Post")
            }
        }
    }
}
