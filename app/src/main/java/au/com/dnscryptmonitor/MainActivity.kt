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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

// Lists bigger than this are never loaded into the editor (a 35 MB string crashes the text field).
private const val LIST_FULL_LOAD_BYTES = 512_000L
private const val LIST_PREVIEW_LINES = 300

private data class ListData(val text: String, val bytes: Long, val lines: Long, val truncated: Boolean)

private fun humanSize(b: Long): String = when {
    b >= 1_048_576 -> String.format("%.1f MB", b / 1_048_576.0)
    b >= 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> "$b B"
}

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
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(Prefs.theme(c)) }
    var buttonStyle by remember { mutableStateOf(UiPrefs.buttonStyle(c)) }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableIntStateOf(0) }
        var base by remember { mutableStateOf(Prefs.base(c)) }
        var allowed by remember { mutableStateOf("") }
        var blocked by remember { mutableStateOf("") }
        var allowedInfo by remember { mutableStateOf(ListData("", 0, 0, false)) }
        var blockedInfo by remember { mutableStateOf(ListData("", 0, 0, false)) }
        var query by remember { mutableStateOf("") }
        var searchHits by remember { mutableStateOf<List<String>?>(null) }
        var pendingRemove by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        // Single entry point for restarting the proxy. Optional [before] must succeed first.
        suspend fun restartProxy(label: String = "Restart proxy", before: String? = null) {
            run(label, ProxyControl.restartCommand(before))
        }
        var redirectActive by remember { mutableStateOf<Boolean?>(null) }
        suspend fun refreshRedirect() { redirectActive = withContext(Dispatchers.IO) { DnsRedirect.isActive() } }
        LaunchedEffect(tab) { if (tab == 0) refreshRedirect() }
        // Small files are loaded whole; big ones only get a read-only preview.
        suspend fun readList(path: String): ListData = withContext(Dispatchers.IO) {
            val q = RootShell.q(path)
            val bytes = RootShell.run("wc -c < $q 2>/dev/null").output.trim().toLongOrNull() ?: 0L
            if (bytes <= LIST_FULL_LOAD_BYTES) {
                ListData(RootShell.run("cat $q 2>/dev/null").output, bytes, 0, false)
            } else {
                val lines = RootShell.run("wc -l < $q 2>/dev/null").output.trim().toLongOrNull() ?: 0L
                ListData(RootShell.run("head -n $LIST_PREVIEW_LINES $q 2>/dev/null").output, bytes, lines, true)
            }
        }
        suspend fun searchList(path: String, text: String): List<String> = withContext(Dispatchers.IO) {
            RootShell.run("grep -i -F -m 200 -- ${RootShell.q(text.trim())} ${RootShell.q(path)} 2>/dev/null").output
                .lines().filter { it.isNotBlank() }
        }
        suspend fun loadLists() {
            allowedInfo = readList(Prefs.allow(c))
            blockedInfo = readList(Prefs.block(c))
            allowed = allowedInfo.text
            blocked = blockedInfo.text
            searchHits = null
        }
        suspend fun removeEntry(path: String, line: String) {
            val r = withContext(Dispatchers.IO) { ListEdit.removeExact(path, line) }
            if (r.code != 0) { toast("Remove failed: ${r.output}"); return }
            val hadSearch = searchHits != null
            loadLists()
            if (hadSearch && query.isNotBlank()) searchHits = searchList(path, query)
            restartProxy("Remove entry and restart")
        }
        suspend fun loadRecent() {
            recent = withContext(Dispatchers.IO) { SeenDb(c).use { it.recent() } }
        }
        pendingRemove?.let { (path, line) ->
            val file = path.substringAfterLast('/')
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("Remove entry") },
                text = { Text("Remove \"$line\" from $file? A backup is saved as $file.bak and the proxy restarts.") },
                confirmButton = {
                    TextButton(onClick = { pendingRemove = null; scope.launch { removeEntry(path, line) } }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } }
            )
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
                        restartProxy()
                    }
                }) { Icon(Icons.Default.Refresh, "Refresh / restart") }
            })
        }, bottomBar = {
            NavigationBar {
                val labels = listOf("Dashboard", "Recent", "Domain lists", "Blocklist", "Settings")
                val icons = listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.List, Icons.Default.Build, Icons.Default.Settings)
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
                        ButtonGroup(buttonStyle) {
                            AppButton(buttonStyle, "Start / resume monitoring", Icons.Default.PlayArrow, primary = true) {
                                ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
                                status = "Monitoring started"
                            }
                            AppButton(buttonStyle, "Stop monitoring", Icons.Default.Stop) {
                                c.stopService(Intent(c, MonitorService::class.java)); status = "Monitoring stopped"
                            }
                        }
                        HorizontalDivider()
                        Text("DNS protection", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (redirectActive) {
                                true -> "DNS redirect is active: DNS queries go to dnscrypt-proxy."
                                false -> "DNS redirect is paused: DNS is NOT going through dnscrypt-proxy."
                                null -> "Checking DNS redirect…"
                            }
                        )
                        ButtonGroup(buttonStyle) {
                            if (redirectActive == false) {
                                AppButton(buttonStyle, "Resume DNS protection", Icons.Default.PlayArrow, primary = true) {
                                    scope.launch { run("Resume DNS protection", DnsRedirect.RESUME_CMD); refreshRedirect() }
                                }
                            } else {
                                AppButton(buttonStyle, "Pause DNS protection", Icons.Default.Pause, destructive = true) {
                                    scope.launch { run("Pause DNS protection", DnsRedirect.PAUSE_CMD); refreshRedirect() }
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("Maintenance", style = MaterialTheme.typography.titleLarge)
                        ButtonGroup(buttonStyle) {
                            AppButton(buttonStyle, "Restart dnscrypt-proxy", Icons.Default.Refresh) {
                                scope.launch { restartProxy() }
                            }
                            AppButton(buttonStyle, "Clear blocked-names.log + restart", Icons.Default.DeleteSweep, destructive = true) {
                                scope.launch { restartProxy("Clear log and restart", ": > ${RootShell.q(Prefs.log(c))}") }
                            }
                            AppButton(buttonStyle, "Purge seen + recent DB + restart", Icons.Default.DeleteForever, destructive = true) {
                                scope.launch {
                                    SeenDb(c).use { it.purge() }
                                    count = 0
                                    recent = emptyList()
                                    restartProxy("Purge DB and restart")
                                }
                            }
                            AppButton(buttonStyle, "Refresh recent log / database count", Icons.Default.Refresh) {
                                count = SeenDb(c).use { it.count() }
                                scope.launch {
                                    log = withContext(Dispatchers.IO) {
                                        RootShell.run("tail -n 20 ${RootShell.q(Prefs.log(c))} 2>/dev/null").output
                                    }
                                    loadRecent()
                                }
                            }
                        }
                        Text("Seen domains: $count")
                        if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                        Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Recently blocked", style = MaterialTheme.typography.headlineSmall)
                        Text("Up to 200 unique domains, newest activity first. Allow adds the domain to allowed-names.txt and requests a proxy restart.")
                        ButtonGroup(buttonStyle) {
                            AppButton(buttonStyle, "Refresh", Icons.Default.Refresh) { scope.launch { loadRecent() } }
                            AppButton(buttonStyle, "Restart proxy", Icons.Default.RestartAlt) { scope.launch { restartProxy() } }
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
                                        AppButton(buttonStyle, "Allow + restart proxy", Icons.Default.Check, primary = true) {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { DomainAllow.allow(c, item.domain) }
                                                if (result.code == 0) {
                                                    toast("Allowed ${item.domain}; proxy restart requested.")
                                                    loadLists()
                                                } else {
                                                    toast("Allow failed: ${result.output}")
                                                }
                                            }
                                        }
                                        val rule = item.pattern
                                        if (!rule.isNullOrBlank()) {
                                            AppButton(buttonStyle, "Remove rule from blocked list", Icons.Default.Delete, destructive = true) {
                                                pendingRemove = Prefs.block(c) to rule
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        val info = if (showAllowed) allowedInfo else blockedInfo
                        val listPath = if (showAllowed) Prefs.allow(c) else Prefs.block(c)
                        Text("Domain lists", style = MaterialTheme.typography.headlineSmall)
                        Text("One entry per line. Root permission is required to read or write protected paths.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(showAllowed, { showAllowed = true; searchHits = null }, label = { Text("Allowed") })
                            FilterChip(!showAllowed, { showAllowed = false; searchHits = null }, label = { Text("Blocked") })
                            TextButton(onClick = { scope.launch { loadLists() } }) { Text("Reload") }
                        }
                        if (info.truncated) {
                            Text(
                                "Large file (${humanSize(info.bytes)}, ${info.lines} lines). Showing the first $LIST_PREVIEW_LINES lines, read-only, " +
                                    "so the app doesn't run out of memory and the file can't be overwritten with a partial copy. Search for an entry to remove it (a .bak backup is kept).",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    query, { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                                    label = { Text("Search (max 200 matches)") }
                                )
                                IconButton(onClick = {
                                    scope.launch { searchHits = if (query.isBlank()) null else searchList(listPath, query) }
                                }) { Icon(Icons.Default.Search, "Search") }
                            }
                        }
                        val hits = searchHits
                        if (info.truncated && hits != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (hits.isEmpty()) "No matches" else "${hits.size} match(es)" + (if (hits.size >= 200) ", first 200 shown" else ""),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { searchHits = null }) { Text("Clear search") }
                            }
                            hits.forEach { hit ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(hit, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        IconButton(onClick = { pendingRemove = listPath to hit }) {
                                            Icon(Icons.Default.Delete, "Remove from list")
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = if (info.truncated) info.text else if (showAllowed) allowed else blocked,
                                onValueChange = {
                                    if (!info.truncated) { if (showAllowed) allowed = it else blocked = it }
                                },
                                readOnly = info.truncated,
                                modifier = Modifier.fillMaxWidth().height(380.dp),
                                label = { Text(if (showAllowed) "allowed-names.txt" else "blocked-names.txt") }
                            )
                        }
                        ButtonGroup(buttonStyle) {
                            AppButton(buttonStyle, "Save list", Icons.Default.Save, primary = true, enabled = !info.truncated) {
                                val value = if (showAllowed) allowed else blocked
                                val marker = "DNSCRYPT_END_8371"
                                scope.launch { run("Save list", "cat > ${RootShell.q(listPath)} <<'$marker'\n$value\n$marker") }
                            }
                            AppButton(buttonStyle, "Restart proxy to apply changes", Icons.Default.RestartAlt) {
                                scope.launch { restartProxy() }
                            }
                        }
                    }
                    3 -> BuildBlocklistTab(buttonStyle) { scope.launch { restartProxy() } }
                    else -> {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            base, { base = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("DNSCrypt directory") },
                            supportingText = { Text("Directory containing the three text/log files") }
                        )
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, label) ->
                                FilterChip(theme == v, { theme = v; Prefs.setTheme(c, v) }, label = { Text(label) })
                            }
                        }
                        Text("Button style", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("list" to "List", "compact" to "Compact", "classic" to "Classic").forEach { (v, label) ->
                                FilterChip(buttonStyle == v, { buttonStyle = v; UiPrefs.setButtonStyle(c, v) }, label = { Text(label) })
                            }
                        }
                        ButtonGroup(buttonStyle) {
                            AppButton(buttonStyle, "Save settings", Icons.Default.Save, primary = true) {
                                Prefs.setBase(c, base)
                                status = "Settings saved"
                                toast(status)
                            }
                        }
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

/** Lays buttons out for the chosen style: wrapping row for "compact", stacked column otherwise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonGroup(style: String, content: @Composable () -> Unit) {
    if (style == "compact") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/**
 * One button, three looks:
 *  - "list":    full-width tonal rows with icon + chevron (settings-screen feel)
 *  - "compact": small content-sized buttons with softly rounded corners
 *  - "classic": the original full-width pill buttons
 */
@Composable
internal fun AppButton(
    style: String,
    label: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    when (style) {
        "list" -> {
            val shape = RoundedCornerShape(14.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(shape)
                    .clickable(enabled = enabled, onClick = onClick),
                shape = shape,
                color = if (primary) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) {
                        Icon(icon, null, tint = if (destructive) scheme.error else scheme.primary)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = if (destructive) scheme.error else if (primary) scheme.onPrimaryContainer else scheme.onSurface
                    )
                    Icon(Icons.Default.ChevronRight, null, tint = scheme.onSurfaceVariant)
                }
            }
        }
        "compact" -> {
            val shape = RoundedCornerShape(10.dp)
            val pad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            val inner: @Composable RowScope.() -> Unit = {
                if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            when {
                primary -> Button(onClick = onClick, enabled = enabled, shape = shape, contentPadding = pad, content = inner)
                destructive -> OutlinedButton(
                    onClick = onClick, enabled = enabled, shape = shape, contentPadding = pad,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error), content = inner
                )
                else -> FilledTonalButton(onClick = onClick, enabled = enabled, shape = shape, contentPadding = pad, content = inner)
            }
        }
        else -> {
            val inner: @Composable RowScope.() -> Unit = {
                if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
                Text(label)
            }
            if (primary) Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), content = inner)
            else OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), content = inner)
        }
    }
}
