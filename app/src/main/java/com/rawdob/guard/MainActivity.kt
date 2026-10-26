package com.rawdob.guard

import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
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
                initialBaselineDomains = prefs.getString("privacy_baseline_domains", "").orEmpty(),
                onSaveBaseline = { prefs.edit().putString("privacy_baseline_domains", it).apply() },
                onExportReport = { report ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "RAWDOB local privacy report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Export RAWDOB report"))
                },
                initialApps = emptyList(),
                onLoadApps = { deliver ->
                    lifecycleScope.launch {
                        val apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
                        deliver(apps)
                    }
                },
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
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityPackages = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo.serviceInfo?.packageName }.toSet()
        val notificationListenerPackages = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val deviceAdminPackages = devicePolicyManager.activeAdmins.orEmpty().map { it.packageName }.toSet()
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val sensitivePermissions = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )
        return packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .map { info ->
                val packageInfo = try {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(info.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                } catch (_: Exception) {
                    null
                }
                val permissions = packageInfo?.requestedPermissions.orEmpty()
                val flags = packageInfo?.requestedPermissionsFlags ?: intArrayOf()
                val requestedSensitive = permissions.toSet().intersect(sensitivePermissions)
                val grantedSensitive = permissions.indices.filter { index ->
                    permissions[index] in sensitivePermissions &&
                        index < flags.size &&
                        (flags[index] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                }.map { permissions[it] }.toSet()
                InstalledAppInfo(
                    name = packageManager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    uid = info.uid,
                    sensitivePermissions = requestedSensitive.toList().sorted(),
                    grantedSensitivePermissions = grantedSensitive.toList().sorted(),
                    specialAccess = buildList {
                        if (info.packageName in accessibilityPackages) add("Accessibility service enabled")
                        if (info.packageName in notificationListenerPackages) add("Notification access enabled")
                        if (info.packageName in deviceAdminPackages) add("Device administrator")
                        try {
                            if (appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, info.uid, info.packageName) == AppOpsManager.MODE_ALLOWED) {
                                add("Display over other apps")
                            }
                        } catch (_: Exception) {
                        }
                    },
                    isSystemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.name.lowercase() })
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

private data class InstalledAppInfo(val name: String, val packageName: String, val uid: Int, val sensitivePermissions: List<String>, val grantedSensitivePermissions: List<String>, val specialAccess: List<String>, val isSystemApp: Boolean)

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
    initialBaselineDomains: String,
    onSaveBaseline: (String) -> Unit,
    onExportReport: (String) -> Unit,
    initialApps: List<InstalledAppInfo>,
    onLoadApps: (((List<InstalledAppInfo>) -> Unit) -> Unit),
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
    var baselineDomains by remember { mutableStateOf(initialBaselineDomains) }
    val observedDomains = remember(events) {
        events.lines().map { it.substringAfterLast("|").trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.contains(".") && !it.contains(" ") }.distinct()
    }
    val baselineSet = remember(baselineDomains) { baselineDomains.lines().map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet() }
    val newlyObservedDomains = observedDomains.filterNot { it in baselineSet }
    var blocklist by remember { mutableStateOf(initialBlocklist) }
    var usageAccess by remember { mutableStateOf(initialUsageAccess) }
    var networkUsage by remember { mutableStateOf<List<NetworkUsageInfo>>(emptyList()) }
    var usageLoading by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(initialApps) }
    var appsLoading by remember { mutableStateOf(true) }
    var appSearch by remember { mutableStateOf("") }
    val filteredApps = remember(apps, appSearch) {
        val query = appSearch.trim().lowercase(java.util.Locale.ROOT)
        if (query.isEmpty()) apps else apps.filter {
            it.name.lowercase(java.util.Locale.ROOT).contains(query) ||
                it.packageName.lowercase(java.util.Locale.ROOT).contains(query)
        }
    }
    LaunchedEffect(Unit) {
        onLoadApps { loaded -> apps = loaded; appsLoading = false }
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
                        Text("${apps.size} installed apps visible to RAWDOB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Includes visible system and non-launcher packages. Android restrictions can still hide some packages; this is not a running-process list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search apps or package names") },
                            singleLine = true
                        )
                        Text("Showing ${filteredApps.size} of ${apps.size} visible installed apps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (appsLoading) {
                            Text("Scanning installed apps and permissions…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (apps.isEmpty()) {
                            Text("No launchable apps were returned by Android.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (filteredApps.isEmpty()) {
                            Text("No apps match this search.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            filteredApps.forEach { app ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(if (app.isSystemApp) "SYSTEM" else "USER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (app.sensitivePermissions.isNotEmpty()) {
                                        Text(
                                            "Sensitive requested: ${app.sensitivePermissions.size} · granted: ${app.grantedSensitivePermissions.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (app.grantedSensitivePermissions.isNotEmpty()) Color(0xFFFFB454) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            app.sensitivePermissions.joinToString { it.substringAfterLast('.').removePrefix("READ_").removePrefix("WRITE_") },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("PRIVACY AUDIT · SENSITIVE PERMISSIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        val appsWithSensitiveRequests = apps.filter { it.sensitivePermissions.isNotEmpty() }
                        val appsWithGrantedSensitive = initialApps.filter { it.grantedSensitivePermissions.isNotEmpty() }
                        val appsWithSpecialAccess = initialApps.filter { it.specialAccess.isNotEmpty() }
                        Text("${appsWithSensitiveRequests.size} apps request sensitive permissions · ${appsWithGrantedSensitive.size} show granted sensitive permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${appsWithSpecialAccess.size} apps have detected special access (accessibility, notification access, device admin, or overlay)", style = MaterialTheme.typography.bodyMedium, color = if (appsWithSpecialAccess.isNotEmpty()) Color(0xFFFFB454) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("A requested or granted permission is not proof of misuse. Special-access checks are limited to Android settings visible to RAWDOB and do not prove data collection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            val report = buildString {
                                appendLine("RAWDOB LOCAL PRIVACY REPORT")
                                appendLine("Generated: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()))
                                appendLine("Installed apps visible: ${apps.size}")
                                appendLine("Apps requesting sensitive permissions: ${appsWithSensitiveRequests.size}")
                                appendLine("Apps with granted sensitive permissions: ${appsWithGrantedSensitive.size}")
                                appendLine("Apps with detected special access: ${appsWithSpecialAccess.size}")
                                appendLine("DNS requests handled: $queryCount")
                                appendLine("DNS requests blocked: $blockedCount")
                                appendLine("VPN DNS filter active: $active")
                                appendLine()
                                appendLine("APP PERMISSION REVIEW")
                                (appsWithGrantedSensitive + appsWithSpecialAccess).distinctBy { it.packageName }
                                    .sortedByDescending { it.grantedSensitivePermissions.size + it.specialAccess.size }
                                    .forEach { app ->
                                        appendLine("${app.name} (${app.packageName})")
                                        appendLine("  Granted sensitive permissions: " + app.grantedSensitivePermissions.joinToString { it.substringAfterLast('.') }.ifBlank { "none reported" })
                                        appendLine("  Special access: " + app.specialAccess.joinToString().ifBlank { "none detected" })
                                    }
                                appendLine()
                                appendLine("RECENT DNS EVENTS (RAWDOB only)")
                                appendLine(events)
                                appendLine()
                                appendLine("LIMITATIONS")
                                appendLine("This report does not prove data exfiltration. DNS events are not all network connections. HTTPS contents are not inspected. App permission data and special access visibility depend on Android.")
                            }
                            onExportReport(report)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("EXPORT LOCAL PRIVACY REPORT")
                        }
                        (appsWithGrantedSensitive + appsWithSpecialAccess).distinctBy { it.packageName }
                            .sortedByDescending { it.grantedSensitivePermissions.size + it.specialAccess.size }
                            .take(16).forEach { app ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    if (app.grantedSensitivePermissions.isNotEmpty()) {
                                        Text("Granted: " + app.grantedSensitivePermissions.joinToString { it.substringAfterLast('.') }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB454))
                                    }
                                    if (app.specialAccess.isNotEmpty()) {
                                        Text("Special access: " + app.specialAccess.joinToString(), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8F8F))
                                    }
                                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        if (appsWithGrantedSensitive.isEmpty() && appsWithSpecialAccess.isEmpty()) {
                            Text("No granted sensitive permissions or listed special-access indicators were reported for the visible app set.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("LOCAL NETWORK BASELINE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("${newlyObservedDomains.size} domains in recent DNS events are not in your saved baseline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (newlyObservedDomains.isNotEmpty()) Color(0xFFFFB454) else Color(0xFF5CF2C2))
                        Text("This compares only the recent DNS event window with a baseline you save. A new domain is not proof of tracking, malware, or data leakage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        newlyObservedDomains.take(8).forEach { domain ->
                            Text("• $domain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = {
                            baselineDomains = observedDomains.joinToString("\n")
                            onSaveBaseline(baselineDomains)
                        }, modifier = Modifier.fillMaxWidth(), enabled = observedDomains.isNotEmpty()) {
                            Text("SAVE RECENT DOMAINS AS BASELINE")
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("CUSTOM BLOCKLIST", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("Rules: block: ads.example.com or allow: trusted.example.com. A bare domain line still means BLOCK. Invalid entries are ignored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
