package com.rawdob.guard

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                initialBlocked = prefs.getLong(RawdobVpnService.KEY_BLOCKED, 0L),
                initialLastDomain = prefs.getString(RawdobVpnService.KEY_LAST_DOMAIN, "No DNS events yet").orEmpty(),
                initialLastAction = prefs.getString(RawdobVpnService.KEY_LAST_ACTION, "WAITING").orEmpty(),
                initialEvents = prefs.getString(RawdobVpnService.KEY_EVENTS, "").orEmpty(),
                initialApps = loadLaunchableApps(),
                initialUsageAccess = hasUsageStatsAccess(),
                onRequestUsageAccess = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onRefreshNetworkUsage = { deliver ->
                    lifecycleScope.launch {
                        val permitted = hasUsageStatsAccess()
                        val usage = withContext(Dispatchers.IO) { if (permitted) loadNetworkUsage() else emptyList() }
                        deliver(permitted, usage)
                    }
                },
                readStats = {
                    RadarStats(
                        prefs.getBoolean(RawdobVpnService.KEY_ACTIVE, false),
                        prefs.getLong(RawdobVpnService.KEY_QUERIES, 0L),
                        prefs.getLong(RawdobVpnService.KEY_BLOCKED, 0L),
                        prefs.getString(RawdobVpnService.KEY_LAST_DOMAIN, "No DNS events yet").orEmpty(),
                        prefs.getString(RawdobVpnService.KEY_LAST_ACTION, "WAITING").orEmpty(),
                        prefs.getString(RawdobVpnService.KEY_EVENTS, "").orEmpty()
                    )
                },
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

    private fun loadLaunchableApps(): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val info = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                InstalledAppInfo(
                    name = resolveInfo.loadLabel(packageManager).toString(),
                    packageName = info.packageName,
                    uid = info.uid
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun loadNetworkUsage(): List<NetworkUsageInfo> {
        val manager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24L * 60L * 60L * 1000L
        return loadLaunchableApps().map { app ->
            var received = 0L
            var sent = 0L
            listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE).forEach { networkType ->
                try {
                    val stats = manager.queryDetailsForUid(networkType, null, start, end, app.uid)
                    try {
                        val bucket = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            if (stats.getNextBucket(bucket)) {
                                received += bucket.rxBytes
                                sent += bucket.txBytes
                            }
                        }
                    } finally {
                        stats.close()
                    }
                } catch (_: SecurityException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            NetworkUsageInfo(app.name, app.packageName, received, sent)
        }.sortedByDescending { it.receivedBytes + it.sentBytes }
    }

    private fun requestProtection() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnConsent.launch(intent)
        else ContextCompat.startForegroundService(this, Intent(this, RawdobVpnService::class.java))
    }
}

private data class InstalledAppInfo(val name: String, val packageName: String, val uid: Int)

private data class NetworkUsageInfo(val name: String, val packageName: String, val receivedBytes: Long, val sentBytes: Long)

private data class RadarStats(val active: Boolean, val queries: Long, val blocked: Long, val lastDomain: String, val lastAction: String, val events: String)

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
    initialBlocked: Long,
    initialLastDomain: String,
    initialLastAction: String,
    initialEvents: String,
    initialApps: List<InstalledAppInfo>,
    initialUsageAccess: Boolean,
    onRequestUsageAccess: () -> Unit,
    onRefreshNetworkUsage: (((Boolean, List<NetworkUsageInfo>) -> Unit) -> Unit),
    readStats: () -> RadarStats,
    initialBlocklist: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSaveBlocklist: (String) -> Unit
) {
    var active by remember { mutableStateOf(initiallyActive) }
    var queryCount by remember { mutableStateOf(initialQueries) }
    var blockedCount by remember { mutableStateOf(initialBlocked) }
    var lastDomain by remember { mutableStateOf(initialLastDomain) }
    var lastAction by remember { mutableStateOf(initialLastAction) }
    var events by remember { mutableStateOf(initialEvents) }
    var blocklist by remember { mutableStateOf(initialBlocklist) }
    var usageAccess by remember { mutableStateOf(initialUsageAccess) }
    var networkUsage by remember { mutableStateOf<List<NetworkUsageInfo>>(emptyList()) }
    var usageLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val stats = readStats()
            active = stats.active
            queryCount = stats.queries
            blockedCount = stats.blocked
            lastDomain = stats.lastDomain
            lastAction = stats.lastAction
            events = stats.events
        }
    }
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
                    MetricCard("DNS requests", queryCount.toString(), "processed locally", Modifier.weight(1f))
                    MetricCard("Blocked", blockedCount.toString(), "matched block rules", Modifier.weight(1f))
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("APP INVENTORY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("${initialApps.size} launchable apps detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("This is the installed-app list, not a list of running processes or per-app network connections.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (initialApps.isEmpty()) {
                            Text("No launchable apps were returned by Android.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            initialApps.take(16).forEach { app ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (initialApps.size > 16) Text("Showing 16 of ${initialApps.size} apps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("NETWORK USAGE BY APP · LAST 24 HOURS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text(if (usageAccess) "Usage Access detected" else "Usage Access permission required", style = MaterialTheme.typography.bodyMedium, color = if (usageAccess) Color(0xFF5CF2C2) else Color(0xFFFFB454))
                        Text("Android-provided per-UID byte totals where available. Results may be delayed or unavailable on some devices and are not live packet attribution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestUsageAccess, modifier = Modifier.weight(1f)) { Text("GRANT ACCESS") }
                            Button(onClick = {
                                usageLoading = true
                                onRefreshNetworkUsage { permitted, rows ->
                                    usageAccess = permitted
                                    networkUsage = rows
                                    usageLoading = false
                                }
                            }, modifier = Modifier.weight(1f), enabled = !usageLoading) { Text(if (usageLoading) "READING…" else "REFRESH") }
                        }
                        if (!usageAccess) {
                            Text("Enable RAWDOB under Android's Usage Access settings, return here, then tap REFRESH.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (networkUsage.isEmpty() && !usageLoading) {
                            Text("No network usage rows loaded yet. Tap REFRESH to query the last 24 hours.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            networkUsage.take(10).forEach { row ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("↓ ${formatBytes(row.receivedBytes)}   ↑ ${formatBytes(row.sentBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(row.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("LIVE DNS RADAR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text(lastDomain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Latest action: $lastAction",
                            color = if (lastAction == "BLOCKED") Color(0xFFFF8A8A) else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("RECENT BACKGROUND DNS EVENTS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        if (events.isBlank()) {
                            Text("No DNS events recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            events.lines().filter { it.isNotBlank() }.take(20).forEach { event ->
                                val eventColor = when {
                                    event.contains("| BLOCKED |") -> Color(0xFFFF6666)
                                    event.contains("| ALLOWED |") -> Color(0xFF5CF2C2)
                                    else -> Color(0xFFFFB454)
                                }
                                Text(event, style = MaterialTheme.typography.bodySmall, color = eventColor)
                            }
                        }
                        Text("Live log of DNS requests handled by RAWDOB only; Android does not expose every app process or encrypted packet through this DNS filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MiB", mib)
    return String.format(java.util.Locale.ROOT, "%.2f GiB", mib / 1024.0)
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
        val riskColor = when (result.level) {
            UrlRiskLevel.LOW -> Color(0xFF5CF2C2)
            UrlRiskLevel.REVIEW -> Color(0xFFFFB454)
            UrlRiskLevel.HIGH -> Color(0xFFFF6666)
            UrlRiskLevel.INVALID -> Color(0xFFFFB454)
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = riskColor)
        result.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = riskColor) }
        Text(result.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
