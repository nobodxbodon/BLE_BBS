package com.wuxuan.blemvp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wuxuan.blemvp.ble.BleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleEngine: BleEngine
    private val statusText = mutableStateOf("IDLE")
    private val localAddressText = mutableStateOf("Unavailable")
    private val eventLines = mutableStateListOf<String>()

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
        localAddressText.value = bleEngine.getLocalDeviceAddress()
        requestBlePermissionsIfNeeded()
        bleEngine.setLifecycleListener { state, detail ->
            runOnUiThread {
                statusText.value = state.name
                val line = if (detail.isBlank()) state.name else "${state.name}: $detail"
                eventLines.add(0, line)
                if (eventLines.size > 40) {
                    eventLines.removeAt(eventLines.lastIndex)
                }
            }
        }

        setContent {
            MaterialTheme {
                var inputText by remember { mutableStateOf("") }
                BleStatusScreen(
                    localAddress = localAddressText.value,
                    statusText = statusText.value,
                    events = eventLines,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        val msg = inputText.trim()
                        if (msg.isNotEmpty()) {
                            val sendCount = bleEngine.sendMessageToAllPeers(msg)
                            if (sendCount > 0) {
                                eventLines.add(0, "SENT($sendCount): $msg")
                            } else {
                                val snapshot = bleEngine.getPeerSnapshot()
                                val reason = when {
                                    snapshot.writableCount == 0 && snapshot.activeGattCount == 0 && snapshot.pendingCount > 0 ->
                                        "waiting for connection"
                                    snapshot.writableCount == 0 && snapshot.activeGattCount > 0 ->
                                        "connected, waiting for writable characteristic"
                                    snapshot.writableCount > 0 ->
                                        "write failed (peer busy/reconnecting)"
                                    else -> "no writable peer"
                                }

                                if (snapshot.writableCount > 0) {
                                    eventLines.add(0, "SEND_RETRY: scheduling retry for '$msg'")
                                    lifecycleScope.launch {
                                        delay(300)
                                        val retryCount = bleEngine.sendMessageToAllPeers(msg)
                                        if (retryCount > 0) {
                                            eventLines.add(0, "SENT_RETRY($retryCount): $msg")
                                        } else {
                                            eventLines.add(0, "SEND_FAIL: $reason")
                                        }
                                    }
                                } else {
                                    eventLines.add(0, "SEND_FAIL: $reason")
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

@Composable
private fun BleStatusScreen(
    localAddress: String,
    statusText: String,
    events: List<String>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "BLEOfflineMVP Android")
        Text(text = "My MAC: $localAddress", modifier = Modifier.padding(top = 4.dp))
        Text(text = "Status: $statusText", modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            label = { Text("Type message") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = onSend,
            enabled = inputText.trim().isNotEmpty(),
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
        ) {
            Text("Send")
        }

        Button(onClick = onStart, modifier = Modifier.padding(top = 16.dp)) {
            Text("Start BLE")
        }

        Button(
            onClick = onStop,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Stop")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Live events (latest first)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .weight(1f, fill = true)
        ) {
            if (events.isEmpty()) {
                item { Text(text = "No BLE events yet") }
            } else {
                items(events) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
