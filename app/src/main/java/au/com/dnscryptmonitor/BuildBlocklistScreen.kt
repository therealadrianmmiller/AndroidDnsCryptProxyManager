package au.com.dnscryptmonitor

import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

private fun mb(bytes: Long) = String.format("%.1f MB", bytes / 1_048_576.0)

/** Emits its content straight into the parent Column (same as the other tabs). */
@Composable
fun BuildBlocklistTab(buttonStyle: String, onRestartProxy: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { mutableStateListOf<BlocklistSource>().apply { addAll(BlocklistPrefs.sources(c)) } }
    var ignoreFailures by remember { mutableStateOf(BlocklistPrefs.ignoreFailures(c)) }
    var timeoutText by remember { mutableStateOf(BlocklistPrefs.timeoutSec(c).toString()) }
    var collapse by remember { mutableStateOf(BlocklistPrefs.collapse(c)) }
    var schedOn by remember { mutableStateOf(BlocklistPrefs.scheduleEnabled(c)) }
    var weekly by remember { mutableStateOf(BlocklistPrefs.scheduleWeekly(c)) }
    var hourText by remember { mutableStateOf(BlocklistPrefs.scheduleHour(c).toString()) }
    var restartAfter by remember { mutableStateOf(BlocklistPrefs.restartAfter(c)) }
    var showAdd by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var showLocal by remember { mutableStateOf(false) }
    var localText by remember { mutableStateOf("") }
    var localCount by remember { mutableStateOf<Int?>(null) }   // null = loading, -1 = unreadable
    var busy by remember { mutableStateOf("") }
    val st by BlocklistStatus.state.collectAsState()

    fun toast(s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
    fun save() = BlocklistPrefs.saveSources(c, sources.toList())
    fun timeoutMs() = (timeoutText.toIntOrNull() ?: 30).coerceIn(1, 600) * 1000
    fun saveSchedule() {
        BlocklistPrefs.setSchedule(c, schedOn, weekly, hourText.toIntOrNull()?.coerceIn(0, 23) ?: 3, restartAfter)
        BlocklistScheduler.rearm(c)
    }

    suspend fun fetchList() {
        busy = "Fetching list from GitHub…"
        try {
            val urls = withContext(Dispatchers.IO) { BlocklistSources.fetchHagezi(timeoutMs()) }
            if (urls.isEmpty()) { toast("No *onlydomains* files found"); return }
            val existing = sources.associateBy { it.url }
            val urlSet = urls.toSet()
            val merged = ArrayList<BlocklistSource>()
            urls.forEach { merged.add(existing[it]?.copy(custom = false) ?: BlocklistSource(it, false, false)) }
            sources.filter { it.custom && it.url !in urlSet }.forEach { merged.add(it) }
            sources.clear(); sources.addAll(merged); save()
        } catch (e: Exception) {
            toast("Could not fetch list: ${e.message}")
        } finally {
            busy = ""
        }
    }

    suspend fun checkSizes() {
        val targets = sources.filter { it.selected }
        if (targets.isEmpty()) { toast("Select at least one source"); return }
        for ((n, s) in targets.withIndex()) {
            busy = "Checking sizes ${n + 1}/${targets.size}…"
            val size = withContext(Dispatchers.IO) { BlocklistSources.headSize(s.url, timeoutMs()) }
            val idx = sources.indexOfFirst { it.url == s.url }
            if (idx >= 0) sources[idx] = sources[idx].copy(sizeBytes = size)
        }
        save()
        busy = ""
    }

    suspend fun refreshLocalCount() {
        val text = withContext(Dispatchers.IO) { LocalAdditions.read(c) }
        localCount = text?.let { LocalAdditions.countDomains(it) } ?: -1
    }

    suspend fun openLocal() {
        val text = withContext(Dispatchers.IO) { LocalAdditions.read(c) }
        if (text == null) { toast("Root access unavailable: can't read ${LocalAdditions.FILE_NAME}"); return }
        localText = if (text.isBlank()) LocalAdditions.TEMPLATE else text
        showLocal = true
    }

    LaunchedEffect(Unit) { if (sources.none { !it.custom }) fetchList() }
    LaunchedEffect(Unit) { refreshLocalCount() }

    if (st.offerRestart) {
        AlertDialog(
            onDismissRequest = { BlocklistStatus.state.value = BlocklistStatus.state.value.copy(offerRestart = false) },
            title = { Text("Blocklist built") },
            text = { Text("Restart dnscrypt-proxy now so it loads the new list?") },
            confirmButton = {
                TextButton(onClick = {
                    BlocklistStatus.state.value = BlocklistStatus.state.value.copy(offerRestart = false)
                    onRestartProxy()
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = {
                    BlocklistStatus.state.value = BlocklistStatus.state.value.copy(offerRestart = false)
                }) { Text("Later") }
            }
        )
    }

    if (showLocal) {
        AlertDialog(
            onDismissRequest = { showLocal = false },
            title = { Text("Local additions") },
            text = {
                OutlinedTextField(
                    localText, { localText = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    label = { Text(LocalAdditions.FILE_NAME) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LocalAdditions.write(c, localText) }
                        if (r.code == 0) {
                            showLocal = false
                            refreshLocalCount()
                            toast("Saved ${LocalAdditions.FILE_NAME}")
                        } else {
                            toast("Save failed (${r.code}): ${r.output}")
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showLocal = false }) { Text("Cancel") } }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add blocklist") },
            text = {
                OutlinedTextField(
                    addText, { addText = it }, singleLine = true,
                    label = { Text("https:// URL of a domain list") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = addText.trim()
                    if (!u.startsWith("https://")) {
                        toast("Enter an https:// URL")
                    } else {
                        if (sources.none { it.url == u }) { sources.add(BlocklistSource(u, true, true)); save() }
                        addText = ""; showAdd = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }

    Text("Build blocklist", style = MaterialTheme.typography.headlineSmall)
    Text("Merge the selected sources into blocked-names.txt. The current file is kept as blocked-names.bak.")

    Text("Blocklist Sources", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(48.dp))
        Text("URL", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Size", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
    }
    if (sources.isEmpty()) Text(if (busy.isNotEmpty()) busy else "No sources yet. Tap Refresh source list or Add blocklist.")
    sources.toList().forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = s.selected, onCheckedChange = { sources[i] = s.copy(selected = it); save() })
            Column(Modifier.weight(1f)) {
                Text(s.url.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.custom) {
                IconButton(onClick = { sources.removeAt(i); save() }) { Icon(Icons.Default.Delete, "Remove source") }
            }
            Text(
                s.sizeBytes?.let { mb(it) } ?: "—",
                Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End
            )
        }
    }
    if (busy.isNotEmpty() && sources.isNotEmpty()) Text(busy, style = MaterialTheme.typography.bodySmall)

    ButtonGroup(buttonStyle) {
        AppButton(buttonStyle, "Add blocklist", Icons.Default.Add) { showAdd = true }
        AppButton(buttonStyle, "Refresh source list", Icons.Default.Refresh, enabled = busy.isEmpty()) {
            scope.launch { fetchList() }
        }
    }
    val selected = sources.filter { it.selected }
    val known = selected.mapNotNull { it.sizeBytes }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            AppButton(buttonStyle, "Check file sizes", Icons.Default.Info, enabled = busy.isEmpty() && selected.isNotEmpty()) {
                scope.launch { checkSizes() }
            }
        }
        Text(
            "Total: " + mb(known.sum()) + if (known.size < selected.size) " (${known.size}/${selected.size} known)" else "",
            Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium
        )
    }

    HorizontalDivider()
    Text("Local additions", style = MaterialTheme.typography.titleMedium)
    Text("Your own domains and dnscrypt-proxy patterns (like ads.*), one per line. ${LocalAdditions.FILE_NAME} is always merged into the built list.")
    Text(
        when (localCount) {
            null -> "Reading ${LocalAdditions.FILE_NAME}…"
            -1 -> "Could not read ${LocalAdditions.FILE_NAME} (is root allowed?)"
            0 -> "No local entries yet"
            else -> "$localCount local entries"
        },
        style = MaterialTheme.typography.bodySmall
    )
    AppButton(buttonStyle, "Edit local additions", Icons.Default.Edit) { scope.launch { openLocal() } }

    HorizontalDivider()
    Text("Build options", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().clickable { ignoreFailures = !ignoreFailures; BlocklistPrefs.setIgnoreFailures(c, ignoreFailures) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ignoreFailures, { ignoreFailures = it; BlocklistPrefs.setIgnoreFailures(c, it) })
        Text("Ignore retrieval failures and continue")
    }
    Row(Modifier.fillMaxWidth().clickable { collapse = !collapse; BlocklistPrefs.setCollapse(c, collapse) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(collapse, { collapse = it; BlocklistPrefs.setCollapse(c, it) })
        Text("Drop subdomains already covered by a listed parent")
    }
    OutlinedTextField(
        timeoutText,
        { v -> timeoutText = v.filter { it.isDigit() }.take(3); BlocklistPrefs.setTimeoutSec(c, (timeoutText.toIntOrNull() ?: 30).coerceIn(1, 600)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text("Connection timeout per URL (seconds)") }
    )

    AppButton(
        buttonStyle, if (st.running) "Building…" else "Build blocklist", Icons.Default.Build,
        primary = true, enabled = !st.running && selected.isNotEmpty()
    ) {
        save()
        try {
            ContextCompat.startForegroundService(c, Intent(c, BlocklistBuildService::class.java))
        } catch (e: Exception) {
            toast("Could not start build: ${e.message}")
        }
    }
    if (st.running || st.log.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (st.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                st.log.takeLast(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    st.lastSummary?.let { Text(it) }

    HorizontalDivider()
    Text("Schedule", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(schedOn, { schedOn = it; saveSchedule() })
        Spacer(Modifier.width(12.dp))
        Text("Rebuild automatically from the saved selection")
    }
    if (schedOn) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!weekly, { weekly = false; saveSchedule() }, label = { Text("Daily") })
            FilterChip(weekly, { weekly = true; saveSchedule() }, label = { Text("Weekly (Sunday)") })
        }
        OutlinedTextField(
            hourText, { v -> hourText = v.filter { it.isDigit() }.take(2); saveSchedule() },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("Hour of day (0–23)") }
        )
        Row(Modifier.fillMaxWidth().clickable { restartAfter = !restartAfter; saveSchedule() },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(restartAfter, { restartAfter = it; saveSchedule() })
            Text("Restart dnscrypt-proxy after a scheduled build")
        }
        val next = BlocklistScheduler.nextTrigger(weekly, hourText.toIntOrNull()?.coerceIn(0, 23) ?: 3)
        Text(
            "Next run: " + DateFormat.getMediumDateFormat(c).format(Date(next)) + " " + DateFormat.getTimeFormat(c).format(Date(next)) +
                " (approximate; Android may delay it slightly)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
