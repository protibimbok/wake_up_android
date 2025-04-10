package io.github.protibimbok.wake_up_android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.protibimbok.wake_up_android.ui.theme.WakeUPTheme
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WakeUPTheme {
                Scaffold { innerPadding ->
                    ServiceManagerScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

    }
}

@Composable
fun ServiceManagerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // remember the toggle state locally
    var isShakeServiceRunning by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ——————————————————————————————————————
        //  Card #1: Shake‑to‑Wake Service Toggle
        // ——————————————————————————————————————
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Shake to Wake Service",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isShakeServiceRunning) "Running" else "Stopped",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = isShakeServiceRunning,
                    onCheckedChange = { enabled ->
                        isShakeServiceRunning = enabled
                        val svcIntent = Intent(context, ShakeService::class.java)
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(svcIntent)
                            } else {
                                context.startService(svcIntent)
                            }
                        } else {
                            context.stopService(svcIntent)
                        }
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ServiceManagerPreview() {
    WakeUPTheme {
        ServiceManagerScreen()
    }
}
