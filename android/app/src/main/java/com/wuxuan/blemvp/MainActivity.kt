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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.wuxuan.blemvp.ble.BleEngine
import com.wuxuan.blemvp.ble.BleLifecycleState
import com.wuxuan.blemvp.storage.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wuxuan.blemvp.storage.PostEntity
import kotlinx.coroutines.flow.Flow

class MainActivity : ComponentActivity() {

    private lateinit var bleEngine: BleEngine
    private val bleStatusText = mutableStateOf("BLE: Stopped")

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleEngine = BleEngine(this)
        val db = AppDatabase.getInstance(this)
        requestBlePermissionsIfNeeded()
        bleEngine.setLifecycleListener { state, detail ->
            runOnUiThread {
                bleStatusText.value = when (state) {
                    BleLifecycleState.RUNNING, BleLifecycleState.CONNECTED -> "BLE: Active"
                    BleLifecycleState.STOPPED -> "BLE: Stopped"
                    BleLifecycleState.ERROR -> "Error: $detail"
                    else -> state.name
                }
            }
        }

        val postsFlow = db.postDao().getAllLatestFirstFlow()

        setContent {
            MaterialTheme {
                var inputText by remember { mutableStateOf("") }
                FeedScreen(
                    postsFlow = postsFlow,
                    statusText = bleStatusText.value,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onPost = {
                        val msg = inputText.trim()
                        if (msg.isNotEmpty()) {
                            val sendCount = bleEngine.sendMessageToAllPeers(msg)
                            if (sendCount == 0) {
                                val snapshot = bleEngine.getPeerSnapshot()
                                if (snapshot.writableCount > 0 || snapshot.activeGattCount > 0) {
                                    lifecycleScope.launch {
                                        delay(400)
                                        bleEngine.sendMessageToAllPeers(msg)
                                    }
                                }
                            }
                            inputText = ""
                        }
                    },
                    onStart = { bleEngine.start() },
                    onStop = { bleEngine.stop() }
                )
            }
        }
    }

    override fun onDestroy() {
        bleEngine.setLifecycleListener(null)
        bleEngine.stop()
        bleEngine.close()
        super.onDestroy()
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
private fun FeedScreen(
    postsFlow: Flow<List<PostEntity>>,
    statusText: String,
    inputText: String,
    onInputChange: (String) -> Unit,
    onPost: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val posts by postsFlow.collectAsState(initial = emptyList())
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // BLE lifecycle controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start BLE") }
            Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop BLE") }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        )

        // Post feed — latest on top, right-aligned, full text (no truncation)
        LazyColumn(
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
                onClick = onPost,
                enabled = inputText.trim().isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("Post")
            }
        }
    }
}
