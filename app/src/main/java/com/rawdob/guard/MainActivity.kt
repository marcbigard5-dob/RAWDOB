package com.rawdob.guard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rawdob.guard.core.UrlRiskChecker
import com.rawdob.guard.core.UrlRiskLevel
import com.rawdob.guard.core.UrlRiskResult
import com.rawdob.guard.net.RawdobVpnService

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(RawdobVpnService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawdobApp(
                initiallyActive = prefs.getBoolean(RawdobVpnService.KEY_ACTIVE, false),
                initialQueries = prefs.getLong(RawdobVpnService.KEY_QUERIES, 0L),
                initialBlocklist = prefs.getString(RawdobVpnService.KEY_BLOCKLIST, "").orEmpty(),
                onStart = { requestProtection() },
                onStop = {
                    startService(Intent(this, RawdobVpnService::class.java).setAction(RawdobVpnService.ACTION_STOP))
                },
                onSaveBlocklist = { prefs.edit().putString(RawdobVpnService.KEY_BLOCKLIST, it).apply() }
            )
        }
    }

    private var startAfterConsent: (() -> Unit)? = null
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            ContextCompat.startForegroundService(this, Intent(this, RawdobVpnService::class.java))
        }
    }

    private fun requestProtection() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnConsent.launch(intent)
        else ContextCompat.startForegroundService(this, Intent(this, RawdobVpnService::class.java))
    }
}

private val RawdobDark = darkColorScheme(
    primary = Color(0xFF5CF2C2),
    onPrimary = Color(0xFF002117),
    secondary = Color(0xFF84D7FF),
    background = Color(0xFF080D12),
    surface = Color(0xFF111A22),
    surfaceVariant = Color(0xFF18242D),
    onSurface = Color(0xFFE7F1F6),
    onSurfaceVariant = Color(0xFF9EB0BC)
)

@Composable
private fun RawdobApp(
    initiallyActive: Boolean,
    initialQueries: Long,
    initialBlocklist: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSaveBlocklist: (String) -> Unit
) {
    var active by remember { mutableStateOf(initiallyActive) }
    var queryCount by remember { mutableStateOf(initialQueries) }
    var blocklist by remember { mutableStateOf(initialBlocklist) }
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UrlRiskResult?>(null) }

    MaterialTheme(colorScheme = RawdobDark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Color(0xFF123D35), shape = MaterialTheme.shapes.large) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Radar, contentDescription = null,
                            tint = Color(0xFF5CF2C2), modifier = Modifier.padding(12.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("RAWDOB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Text("LOCAL PRIVACY DEFENSE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text("DNS", color = Color(0xFF5CF2C2), style = MaterialTheme.typography.labelLarge)
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Icon(Icons.Default.Shield, contentDescription = null, tint = if (active) Color(0xFF5CF2C2) else Color(0xFFFFC078))
                            Column(Modifier.weight(1f)) {
                                Text(if (active) "Protection active" else "Protection stopped", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Local DNS filtering · no account required", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(color = if (active) Color(0xFF164C3D) else Color(0xFF50371E), shape = MaterialTheme.shapes.small) {
                                Text(if (active) "ON" else "OFF", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (active) Color(0xFF5CF2C2) else Color(0xFFFFC078), fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            "RAWDOB filters standard DNS requests routed through its local VPN. It does not inspect HTTPS page contents, and some apps can bypass DNS filtering using encrypted DNS.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                if (active) {
                                    onStop()
                                    active = false
                                } else {
                                    onStart()
                                    active = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) Color(0xFF3C2630) else Color(0xFF5CF2C2),
                                contentColor = if (active) Color(0xFFFFDCE3) else Color(0xFF002117)
                            )
                        ) {
                            Text(if (active) "STOP PROTECTION" else "ENABLE PROTECTION", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("DNS requests", queryCount.toString(), "processed by service", Modifier.weight(1f))
                    MetricCard("Block rules", (3 + blocklist.lines().count { it.trim().isNotEmpty() }).toString(), "built-in + custom", Modifier.weight(1f))
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("CUSTOM BLOCKLIST", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("One domain per line. Example: ads.example.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = blocklist,
                            onValueChange = { blocklist = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            placeholder = { Text("tracker.example\nads.example") }
                        )
                        Button(onClick = { onSaveBlocklist(blocklist) }, modifier = Modifier.fillMaxWidth()) {
                            Text("SAVE BLOCK RULES")
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("URL RISK CHECK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = url, onValueChange = { url = it; result = null },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("https://website.example") }
                        )
                        Button(onClick = { result = UrlRiskChecker.check(url) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("ANALYZE URL")
                        }
                        result?.let { RiskResultCard(it) }
                    }
                }

                Text(
                    "Privacy-first: filtering runs on this device. DNS forwarding for allowed requests uses Cloudflare DNS (1.1.1.1). This version filters IPv4 UDP DNS only; it is not a full-device traffic inspection VPN.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF5CF2C2))
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RiskResultCard(result: UrlRiskResult) {
    val label = when (result.level) {
        UrlRiskLevel.LOW -> "Low risk signals"
        UrlRiskLevel.REVIEW -> "Review recommended"
        UrlRiskLevel.HIGH -> "High-risk signals detected"
        UrlRiskLevel.INVALID -> "Invalid URL"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        result.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(result.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
