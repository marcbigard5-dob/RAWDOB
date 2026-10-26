package com.rawdob.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rawdob.guard.core.UrlRiskChecker
import com.rawdob.guard.core.UrlRiskLevel
import com.rawdob.guard.core.UrlRiskResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RawdobApp() }
    }
}

@Composable
private fun RawdobApp() {
    var learningMode by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UrlRiskResult?>(null) }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("RAWDOB", fontWeight = FontWeight.Bold)
                            Text("Security & Privacy · v0.1.0", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Default.Shield, contentDescription = null)
                            Text("  Privacy status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Local analysis only", style = MaterialTheme.typography.bodyLarge)
                        Text("No VPN tunnel is running. Network enforcement is not enabled in this baseline.")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Learning mode", modifier = Modifier.weight(1f))
                            Switch(checked = learningMode, onCheckedChange = { learningMode = it })
                        }
                        Text(
                            if (learningMode) "Policy decisions are for review; no traffic is blocked."
                            else "This toggle changes the local demo state only. It does not enable system-wide blocking.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Default.Security, contentDescription = null)
                            Text("  URL risk check", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Text("A local heuristic check. It is not a reputation database and cannot guarantee a site is safe.")
                        OutlinedTextField(
                            value = url, onValueChange = { url = it; result = null },
                            modifier = Modifier.fillMaxWidth(), label = { Text("https://example.com") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        Button(onClick = { result = UrlRiskChecker.check(url) }, modifier = Modifier.fillMaxWidth(), enabled = url.isNotBlank()) {
                            Text("Analyze URL")
                        }
                        result?.let { RiskResultCard(it) }
                    }
                }
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Engineering status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        StatusRow("Android UI baseline", "Implemented")
                        StatusRow("Local URL heuristics", "Implemented")
                        StatusRow("Policy engine", "Implemented")
                        StatusRow("VPN packet forwarding", "Not implemented")
                        StatusRow("Cross-app private-file scanning", "Not available to ordinary apps")
                        StatusRow("Device-level kill switch", "Requires Android system VPN settings and a complete VPN")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Security note: risk signals can be incomplete or incorrect. Do not use this baseline as a substitute for a tested security product.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RiskResultCard(result: UrlRiskResult) {
    val label = when (result.level) {
        UrlRiskLevel.LOW -> "Low signal"
        UrlRiskLevel.REVIEW -> "Needs review"
        UrlRiskLevel.HIGH -> "High-risk signals"
        UrlRiskLevel.INVALID -> "Invalid input"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        result.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(result.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
