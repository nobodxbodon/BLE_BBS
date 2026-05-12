package com.wuxuan.blemvp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wuxuan.blemvp.ble.BleEngine

class MainActivity : ComponentActivity() {

    private lateinit var bleEngine: BleEngine

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        bleEngine = BleEngine(this)
        requestBlePermissionsIfNeeded()

        setContent {
            MaterialTheme {
                Day1Screen(
                    onStart = { bleEngine.startDay1Foundation() },
                    onStop = { bleEngine.stopDay1Foundation() }
                )
            }
        }
    }

    override fun onDestroy() {
        bleEngine.stopDay1Foundation()
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
private fun Day1Screen(onStart: () -> Unit, onStop: () -> Unit) {
    var statusText by remember { mutableStateOf("Idle") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "BLEOfflineMVP Android")
        Text(text = "Status: $statusText", modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

        Button(onClick = {
            onStart()
            statusText = "Scanning + Advertising"
        }) {
            Text("Start BLE")
        }

        Button(
            onClick = {
                onStop()
                statusText = "Stopped"
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Stop")
        }
    }
}
