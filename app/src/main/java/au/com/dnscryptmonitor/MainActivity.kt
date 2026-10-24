package au.com.dnscryptmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.channels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppUI() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppUI() {
    val binaryPath = "/system/bin/dnscrypt-proxy"
    val configPath = "/storage/emulated/0/dnscrypt-proxy/dnscrypt-proxy.toml"
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(Prefs.theme(c)) }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableIntStateOf(0) }
        var base by remember { mutableStateOf(Prefs.base(c)) }
        var service by remember { mutableStateOf(Prefs.service(c)) }
        var allowed by remember { mutableStateOf("") }
        var blocked by remember { mutableStateOf("") }
        var showAllowed by remember { mutableStateOf(true) }
        var status by remember { mutableStateOf("Ready") }
        var count by remember { mutableIntStateOf(0) }
        var log by remember { mutableStateOf("") }
        var recent by remember { mutableStateOf(emptyList<RecentBlockedDomain>()) }

        fun toast(s: String) { Toast.makeText(c, s, Toast.LENGTH_LONG).show() }
        suspend fun run(label: String, cmd: String) {
            status = "$label…"
            val r = withContext(Dispatchers.IO) { RootShell.run(cmd, 20000) }
            status = if (r.code == 0) "$label succeeded" else "$label failed (${r.code}): ${r.output}"
            toast(status)
        }
        suspend fun loadLists() {
            allowed = withContext(Dispatchers.IO) { RootShell.run("cat ${RootShell.q(Prefs.allow(c))} 2>/dev/null").output }
            blocked = withContext(Dispatchers.IO) { RootShell.run("cat ${RootShell.q(Prefs.block(c))} 2>/dev/null").output }
        }
        suspend fun loadRecent() {
            recent = withContext(Dispatchers.IO) { SeenDb(c).use { it.recent() } }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("DNSCrypt Monitor") }, actions = {
                IconButton(onClick = {
                    scope.launch {
                        when (tab) {
                            1 -> loadRecent()
                            2 -> loadLists()
                            else -> {
                                count = SeenDb(c).use { it.count() }
                                loadRecent()
                            }
                        }
                        run("Restart proxy", "pkill -f $binaryPath; $binaryPath -config $configPath > /dev/null 2>&1 &")
                    }
                }) { Icon(Icons.Default.Refresh, "Refresh / restart") }
            })
        }, bottomBar = {
            NavigationBar {
                val labels = listOf("Dashboard", "Recent", "Domain lists", "Settings")
                val icons = listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.List, Icons.Default.Settings)
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = {
                            tab = i
                            if (i == 1) scope.launch { loadRecent() }
                            if (i == 2) scope.launch { loadLists() }
                            if (i == 0) {
                                count = SeenDb(c).use { it.count() }
                                scope.launch { loadRecent() }
                            }
                        },
                        icon = { Icon(icons[i], label) },
                        label = { Text(label) }
                    )
                }
            }
        }) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> {
                        Text("Monitoring", style = MaterialTheme.typography.headlineSmall)
                        Text("New domains notify once. All recently blocked domains remain available in the Recent tab.")
                        Button(
                            onClick = {
                                ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
                                status = "Monitoring started"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start / resume monitoring") }
                        OutlinedButton(
                            onClick = { c.stopService(Intent(c, MonitorService::class.java)); status = "Monitoring stopped" },
                            modifier = Modifier.fillMaxWidth()
                        ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop monitoring") }
                        HorizontalDivider()
                        Text("Maintenance", style = MaterialTheme.typography.titleLarge)
                        Action("Restart dnscrypt-proxy", Icons.Default.Refresh) {
                            scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") }
                        }
                        Action("Clear blocked-names.log + restart", Icons.Default.DeleteSweep) {
                            scope.launch { run("Clear log and restart", ": > ${RootShell.q(Prefs.log(c))} && setprop ctl.restart ${RootShell.q(service)}") }
                        }
                        Action("Purge seen + recent DB + restart", Icons.Default.DeleteForever) {
                            scope.launch {
                                SeenDb(c).use { it.purge() }
                                count = 0
                                recent = emptyList()
                                run("Purge DB and restart", "setprop ctl.restart ${RootShell.q(service)}")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                count = SeenDb(c).use { it.count() }
                                scope.launch {
                                    log = withContext(Dispatchers.IO) {
                                        RootShell.run("tail -n 20 ${RootShell.q(Prefs.log(c))} 2>/dev/null").output
                                    }
                                    loadRecent()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Refresh recent log / database count") }
                        Text("Seen domains: $count")
                        if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                        Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Recently blocked", style = MaterialTheme.typography.headlineSmall)
                        Text("Up to 200 unique domains, newest activity first. Allow adds the domain to allowed-names.txt and requests a proxy restart.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { loadRecent() } }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
                            OutlinedButton(onClick = { scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") } }) { Text("Restart proxy") }
                        }
                        if (recent.isEmpty()) {
                            Text("No blocked domains recorded yet.")
                        } else {
                            recent.forEach { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(item.domain, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            DateFormat.getMediumDateFormat(c).format(Date(item.lastSeen)) + " " +
                                                DateFormat.getTimeFormat(c).format(Date(item.lastSeen)),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (!item.pattern.isNullOrBlank()) {
                                            Text("Rule: ${item.pattern}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(onClick = {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { DomainAllow.allow(c, item.domain) }
                                                if (result.code == 0) {
                                                    toast("Allowed ${item.domain}; proxy restart requested.")
                                                    loadLists()
                                                } else {
                                                    toast("Allow failed: ${result.output}")
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Icon(Icons.Default.Check, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Allow + restart proxy")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        Text("Domain lists", style = MaterialTheme.typography.headlineSmall)
                        Text("One entry per line. Root permission is required to read or write protected paths.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(showAllowed, { showAllowed = true }, label = { Text("Allowed") })
                            FilterChip(!showAllowed, { showAllowed = false }, label = { Text("Blocked") })
                            TextButton(onClick = { scope.launch { loadLists() } }) { Text("Reload") }
                        }
                        OutlinedTextField(
                            value = if (showAllowed) allowed else blocked,
                            onValueChange = { if (showAllowed) allowed = it else blocked = it },
                            modifier = Modifier.fillMaxWidth().height(380.dp),
                            label = { Text(if (showAllowed) "allowed-names.txt" else "blocked-names.txt") }
                        )
                        Button(onClick = {
                            val path = if (showAllowed) Prefs.allow(c) else Prefs.block(c)
                            val value = if (showAllowed) allowed else blocked
                            val marker = "DNSCRYPT_END_8371"
                            scope.launch { run("Save list", "cat > ${RootShell.q(path)} <<'$marker'\n$value\n$marker") }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save list")
                        }
                        OutlinedButton(
                            onClick = { scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Restart proxy to apply changes") }
                    }
                    else -> {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            base, { base = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("DNSCrypt directory") },
                            supportingText = { Text("Directory containing the three text/log files") }
                        )
                        OutlinedTextField(
                            service, { service = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Android init service name") },
                            supportingText = { Text("Restart uses setprop ctl.restart SERVICE_NAME") }
                        )
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, label) ->
                                FilterChip(theme == v, { theme = v; Prefs.setTheme(c, v) }, label = { Text(label) })
                            }
                        }
                        Button(
                            onClick = {
                                Prefs.setBase(c, base)
                                Prefs.setService(c, service)
                                status = "Settings saved"
                                toast(status)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save settings") }
                        HorizontalDivider()
                        Text("Log: ${Prefs.log(c)}", style = MaterialTheme.typography.bodySmall)
                        Text("Allowed: ${Prefs.allow(c)}", style = MaterialTheme.typography.bodySmall)
                        Text("Blocked: ${Prefs.block(c)}", style = MaterialTheme.typography.bodySmall)
                        Text("Root access is required. Grant DNSCrypt Monitor root permission in KernelSU when prompted.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
