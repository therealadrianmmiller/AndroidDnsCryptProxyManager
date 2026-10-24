package au.com.dnscryptmonitor
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.channels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppUI() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppUI() {
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
        fun toast(s: String) { Toast.makeText(c, s, Toast.LENGTH_LONG).show() }
        suspend fun run(label: String, cmd: String) {
            status = "$label…"
            val r = withContext(Dispatchers.IO) { RootShell.run(cmd, 20000) }
            status = if (r.code == 0) "$label succeeded" else "$label failed (${r.code}): ${r.output}"
            toast(status)
        }
        suspend fun load() {
            allowed = withContext(Dispatchers.IO) { RootShell.run("cat ${RootShell.q(Prefs.allow(c))} 2>/dev/null").output }
            blocked = withContext(Dispatchers.IO) { RootShell.run("cat ${RootShell.q(Prefs.block(c))} 2>/dev/null").output }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("DNSCrypt Monitor") }, actions = {
            IconButton(onClick = { scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") } }) { Icon(Icons.Default.Refresh, "Restart") }
        }) }, bottomBar = {
            NavigationBar {
                listOf("Dashboard", "Domain lists", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 1) scope.launch { load() }; if (i == 0) count = SeenDb(c).use { it.count() } },
                        icon = { Icon(if (i == 0) Icons.Default.Dashboard else if (i == 1) Icons.Default.List else Icons.Default.Settings, label) }, label = { Text(label) })
                }
            }
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        Text("Monitoring", style = MaterialTheme.typography.headlineSmall)
                        Text("A foreground service watches new log entries. Each domain notifies once until the seen database is purged.")
                        Button(onClick = { ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java)); status = "Monitoring started" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start / resume monitoring") }
                        OutlinedButton(onClick = { c.stopService(Intent(c, MonitorService::class.java)); status = "Monitoring stopped" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop monitoring") }
                        HorizontalDivider()
                        Text("Maintenance", style = MaterialTheme.typography.titleLarge)
                        Action("Restart dnscrypt-proxy", Icons.Default.Refresh) { scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") } }
                        Action("Clear blocked-names.log + restart", Icons.Default.DeleteSweep) { scope.launch { run("Clear log and restart", ": > ${RootShell.q(Prefs.log(c))} && setprop ctl.restart ${RootShell.q(service)}") } }
                        Action("Purge seen DB + restart", Icons.Default.DeleteForever) { scope.launch { SeenDb(c).use { it.purge() }; count = 0; run("Purge DB and restart", "setprop ctl.restart ${RootShell.q(service)}") } }
                        OutlinedButton(onClick = { count = SeenDb(c).use { it.count() }; scope.launch { log = withContext(Dispatchers.IO) { RootShell.run("tail -n 20 ${RootShell.q(Prefs.log(c))} 2>/dev/null").output } } }, modifier = Modifier.fillMaxWidth()) { Text("Refresh recent log / database count") }
                        Text("Seen domains: $count")
                        if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                        Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Domain lists", style = MaterialTheme.typography.headlineSmall)
                        Text("One entry per line. Root permission is required to read or write protected paths.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(showAllowed, { showAllowed = true }, label = { Text("Allowed") })
                            FilterChip(!showAllowed, { showAllowed = false }, label = { Text("Blocked") })
                            TextButton(onClick = { scope.launch { load() } }) { Text("Reload") }
                        }
                        OutlinedTextField(value = if (showAllowed) allowed else blocked, onValueChange = { if (showAllowed) allowed = it else blocked = it },
                            modifier = Modifier.fillMaxWidth().height(380.dp), label = { Text(if (showAllowed) "allowed-names.txt" else "blocked-names.txt") })
                        Button(onClick = {
                            val path = if (showAllowed) Prefs.allow(c) else Prefs.block(c)
                            val value = if (showAllowed) allowed else blocked
                            val marker = "DNSCRYPT_END_8371"
                            scope.launch { run("Save list", "cat > ${RootShell.q(path)} <<'$marker'\n$value\n$marker") }
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save list") }
                        OutlinedButton(onClick = { scope.launch { run("Restart proxy", "setprop ctl.restart ${RootShell.q(service)}") } }, modifier = Modifier.fillMaxWidth()) { Text("Restart proxy to apply changes") }
                    }
                    else -> {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(base, { base = it }, modifier = Modifier.fillMaxWidth(), label = { Text("DNSCrypt directory") },
                            supportingText = { Text("Directory containing the three text/log files") })
                        OutlinedTextField(service, { service = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Android init service name") },
                            supportingText = { Text("Restart uses setprop ctl.restart SERVICE_NAME") })
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, label) ->
                                FilterChip(theme == v, { theme = v; Prefs.setTheme(c, v) }, label = { Text(label) })
                            }
                        }
                        Button(onClick = { Prefs.setBase(c, base); Prefs.setService(c, service); status = "Settings saved"; toast(status) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save settings") }
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
@Composable private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label) }
}
