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

    private lateinit var bleEngine: 蓝牙引擎
    private val bleStatusFlow = MutableStateFlow("BLE: starting…")

    private val requiredPermissions: Array<String>
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
        bleEngine = 蓝牙引擎(this)
        bleEngine.设置生命周期监听器 { state, detail ->
            bleStatusFlow.value = "[$state] $detail"
        }
        val db = AppDatabase.getInstance(this)
        requestBlePermissionsIfNeeded()

        val postsFlow = db.postDao().getAllLatestFirstFlow()

        // Start BLE immediately if permissions are already granted (e.g. re-launch after first run).
        // On first install, 启动 is deferred to onRequestPermissionsResult.
        if (hasAllPermissions()) bleEngine.启动()

        setContent {
            MaterialTheme {
                var inputText by remember { mutableStateOf("") }
                val bleStatus by bleStatusFlow.collectAsState()
                帖子流界面(
                    postsFlow = postsFlow,
                    bleStatus = bleStatus,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onForceSync = { bleEngine.强制同步() },
                    onPost = {
                        val msg = inputText.trim()
                        if (msg.isNotEmpty()) {
                            val (sendCount, encodedBytes) = bleEngine.发送帖子给所有邻机(msg)
                            if (sendCount == 0) {
                                val snapshot = bleEngine.获取邻机快照()
                                if (snapshot.可写邻机数 > 0) {
                                    lifecycleScope.launch {
                                        delay(400)
                                        bleEngine.重试发送给所有邻机(encodedBytes)
                                    }
                                }
                            }
                            inputText = ""
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart scan + advertising every time the user brings the app to the foreground.
        if (::bleEngine.isInitialized) bleEngine.重启扫描()
    }

    override fun onDestroy() {
        bleEngine.设置生命周期监听器(null)
        bleEngine.停止()
        bleEngine.关闭()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_BLE_PERMS && hasAllPermissions()) {
            bleEngine.启动()
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissionsIfNeeded() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_BLE_PERMS)
        }
    }

    companion object {
        private const val REQUEST_CODE_BLE_PERMS = 1001
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun 帖子流界面(
    postsFlow: Flow<List<PostEntity>>,
    bleStatus: String,
    inputText: String,
    onInputChange: (String) -> Unit,
    onForceSync: () -> Unit,
    onPost: () -> Unit
) {
    val posts by postsFlow.collectAsState(initial = emptyList())
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        // Debug toggle row
        var showDebug by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDebug) {
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
                color = if (showDebug) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .combinedClickable(
                        onClick = { showDebug = !showDebug },
                        onLongClick = { onForceSync() }
                    )
                    .padding(4.dp)
            )
        }
        // Post feed — latest on top, right-aligned, full text (no truncation)
        val listState = rememberLazyListState()
        // Auto-scroll to top whenever the newest post changes (local or received)
        LaunchedEffect(posts.firstOrNull()?.id) {
            if (posts.isNotEmpty()) listState.animateScrollToItem(0)
        }
        LazyColumn(
            state = listState,
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
                items(posts, key = { it.id }) { post ->
                    Text(
                        text = post.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboardManager.setText(AnnotatedString(post.text))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
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
                value = inputText,
                onValueChange = onInputChange,
                placeholder = { Text("Type a post") },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4
            )
            Button(
                onClick = {
                    onPost()
                    focusManager.clearFocus()
                },
                enabled = inputText.trim().isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("Post")
            }
        }
    }
}
