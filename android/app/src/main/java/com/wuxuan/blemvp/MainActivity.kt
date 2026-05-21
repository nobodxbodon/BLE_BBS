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

    private lateinit var 蓝牙引擎实例: 蓝牙引擎
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        蓝牙引擎实例 = 蓝牙引擎(this)
        蓝牙引擎实例.设置生命周期监听器 { state, detail ->
            蓝牙状态流.value = "[$state] $detail"
        }
        val db = AppDatabase.getInstance(this)
        按需请求蓝牙权限()

        val 帖文流 = db.postDao().getAllLatestFirstFlow()

        // Start BLE immediately if permissions are already granted (e.g. re-launch after first run).
        // On first install, start is deferred to onRequestPermissionsResult.
        if (具备全部权限()) 蓝牙引擎实例.启动()

        setContent {
            MaterialTheme {
                var 输入文本 by remember { mutableStateOf("") }
                val bleStatus by 蓝牙状态流.collectAsState()
                帖文列表界面(
                    帖文流 = 帖文流,
                    bleStatus = bleStatus,
                    输入文本 = 输入文本,
                    输入变化 = { 输入文本 = it },
                    强制同步 = { 蓝牙引擎实例.强制同步历史() },
                    发布 = {
                        val msg = 输入文本.trim()
                        if (msg.isNotEmpty()) {
                            val (sendCount, encodedBytes) = 蓝牙引擎实例.发送帖文给所有对端(msg)
                            if (sendCount == 0) {
                                val snapshot = 蓝牙引擎实例.获取对等快照()
                                if (snapshot.可写数 > 0) {
                                    lifecycleScope.launch {
                                        delay(400)
                                        蓝牙引擎实例.重试发送给所有对端(encodedBytes)
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
        if (::蓝牙引擎实例.isInitialized) 蓝牙引擎实例.重启扫描广播()
    }

    override fun onDestroy() {
        蓝牙引擎实例.设置生命周期监听器(null)
        蓝牙引擎实例.停止()
        蓝牙引擎实例.关闭()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 蓝牙权限请求码 && 具备全部权限()) {
            蓝牙引擎实例.启动()
        }
    }

    private fun 具备全部权限() = 所需权限.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun 按需请求蓝牙权限() {
        val missing = 所需权限.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 蓝牙权限请求码)
        }
    }

    companion object {
        private const val 蓝牙权限请求码 = 1001
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun 帖文列表界面(
    帖文流: Flow<List<PostEntity>>,
    bleStatus: String,
    输入文本: String,
    输入变化: (String) -> Unit,
    强制同步: () -> Unit,
    发布: () -> Unit
) {
    val 帖文列表 by 帖文流.collectAsState(initial = emptyList())
    val 剪贴板 = LocalClipboardManager.current
    val context = LocalContext.current
    val 焦点管理器 = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        // Debug toggle row
        var 显示调试 by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (显示调试) {
                Text(
                    text = bleStatus,
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
                color = if (显示调试) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .combinedClickable(
                        onClick = { 显示调试 = !显示调试 },
                        onLongClick = { 强制同步() }
                    )
                    .padding(4.dp)
            )
        }
        // Post feed — latest on top, right-aligned, full text (no truncation)
        val 列表状态 = rememberLazyListState()
        // Auto-scroll to top whenever the newest 帖文项 changes (local or received)
        LaunchedEffect(帖文列表.firstOrNull()?.id) {
            if (帖文列表.isNotEmpty()) 列表状态.animateScrollToItem(0)
        }
        LazyColumn(
            state = 列表状态,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            if (帖文列表.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无帖子",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(帖文列表, key = { it.id }) { 帖文项 ->
                    Text(
                        text = 帖文项.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    剪贴板.setText(AnnotatedString(帖文项.text))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
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
                placeholder = { Text("Type a 帖文项") },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4
            )
            Button(
                onClick = {
                    发布()
                    焦点管理器.clearFocus()
                },
                enabled = 输入文本.trim().isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("发布")
            }
        }
    }
}
