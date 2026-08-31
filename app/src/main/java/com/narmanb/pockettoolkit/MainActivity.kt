package com.narmanb.pockettoolkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.narmanb.pockettoolkit.data.DiagnosticsCollector
import com.narmanb.pockettoolkit.data.DuplicateGroup
import com.narmanb.pockettoolkit.data.ScanResult
import com.narmanb.pockettoolkit.data.ScannedFile
import com.narmanb.pockettoolkit.data.StorageScanner
import com.narmanb.pockettoolkit.patch.RomPatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PocketToolkitApp()
            }
        }
    }
}

private enum class Module(val title: String) {
    STORAGE("Storage"), DUPLICATES("Duplicates"), DIAGNOSTICS("Diagnostics"), PATCHER("ROM Patcher")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PocketToolkitApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(Module.STORAGE) }
    var selectedTree by rememberSaveable { mutableStateOf<String?>(null) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun scan(uri: Uri) {
        scope.launch {
            scanning = true
            scanError = null
            runCatching { StorageScanner.scan(context, uri) }
                .onSuccess { scanResult = it }
                .onFailure { scanError = it.message ?: it.javaClass.simpleName }
            scanning = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedTree = uri.toString()
            scan(uri)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pocket Toolkit") }) },
        bottomBar = {
            NavigationBar {
                Module.entries.forEach { module ->
                    NavigationBarItem(
                        selected = selected == module,
                        onClick = { selected = module },
                        icon = { Text(module.title.take(1)) },
                        label = { Text(module.title) }
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            Module.STORAGE -> StorageScreen(
                modifier = Modifier.padding(padding),
                selectedTree = selectedTree,
                result = scanResult,
                scanning = scanning,
                error = scanError,
                onChooseFolder = { folderPicker.launch(selectedTree?.let(Uri::parse)) },
                onRescan = { selectedTree?.let { scan(Uri.parse(it)) } }
            )
            Module.DUPLICATES -> DuplicateScreen(
                modifier = Modifier.padding(padding),
                result = scanResult,
                scanning = scanning,
                onChooseFolder = { folderPicker.launch(selectedTree?.let(Uri::parse)) },
                onDeleted = { selectedTree?.let { scan(Uri.parse(it)) } }
            )
            Module.DIAGNOSTICS -> DiagnosticsScreen(Modifier.padding(padding))
            Module.PATCHER -> PatcherScreen(Modifier.padding(padding))
        }
    }
}

@Composable
private fun StorageScreen(
    modifier: Modifier,
    selectedTree: String?,
    result: ScanResult?,
    scanning: Boolean,
    error: String?,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Storage Analyzer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Choose your ROM/game folder or the root of an SD card. Pocket Toolkit only scans folders you explicitly grant it access to.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onChooseFolder) { Text(if (selectedTree == null) "Choose folder" else "Change folder") }
                if (selectedTree != null) Button(onClick = onRescan, enabled = !scanning) { Text("Rescan") }
            }
        }
        if (scanning) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text("Scanning storage…") } }
        error?.let { item { Text("Scan error: $it", color = MaterialTheme.colorScheme.error) } }
        result?.let { scan ->
            item {
                SummaryCard(
                    title = "Scan summary",
                    lines = listOf(
                        "Files: ${scan.files.size}",
                        "Total size: ${DiagnosticsCollector.formatBytes(scan.totalBytes)}",
                        "Exact duplicate space: ${DiagnosticsCollector.formatBytes(scan.reclaimableBytes)}",
                        "Scan time: ${scan.elapsedMillis / 1000.0} seconds"
                    )
                )
            }
            item { Text("By type", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(scan.categories) { category ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(category.name, fontWeight = FontWeight.SemiBold); Text("${category.count} files") }
                        Text(DiagnosticsCollector.formatBytes(category.bytes))
                    }
                }
            }
            item { Text("Largest files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(scan.files.take(20)) { file -> FileRow(file) }
        }
    }
}

@Composable
private fun DuplicateScreen(
    modifier: Modifier,
    result: ScanResult?,
    scanning: Boolean,
    onChooseFolder: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ScannedFile?>(null) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete duplicate?") },
            text = { Text("Delete ${file.name}? This cannot be undone. Pocket Toolkit never deletes duplicates automatically.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { DocumentFile.fromSingleUri(context, file.uri)?.delete() == true }.getOrDefault(false)
                    deleteMessage = if (ok) "Deleted ${file.name}" else "Could not delete ${file.name}"
                    pendingDelete = null
                    if (ok) onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Duplicate Finder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Exact duplicates are confirmed with SHA-256, not filenames. Same-named files with different contents are not marked as duplicates.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onChooseFolder) { Text(if (result == null) "Choose folder" else "Change folder") }
        }
        if (scanning) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text("Checking files…") } }
        deleteMessage?.let { item { Text(it) } }
        result?.let { scan ->
            item {
                SummaryCard(
                    "Duplicate summary",
                    listOf(
                        "Groups: ${scan.duplicateGroups.size}",
                        "Potentially reclaimable: ${DiagnosticsCollector.formatBytes(scan.reclaimableBytes)}"
                    )
                )
            }
            if (scan.duplicateGroups.isEmpty()) {
                item { Text("No exact duplicate game files were found in this scan.") }
            }
            items(scan.duplicateGroups) { group -> DuplicateGroupCard(group) { pendingDelete = it } }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup, onDelete: (ScannedFile) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${group.files.size} identical files • ${DiagnosticsCollector.formatBytes(group.fileSize)} each", fontWeight = FontWeight.Bold)
            Text("Reclaimable if one copy is kept: ${DiagnosticsCollector.formatBytes(group.wastedBytes)}")
            Text("SHA-256: ${group.sha256.take(20)}…", style = MaterialTheme.typography.bodySmall)
            group.files.forEachIndexed { index, file ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name)
                        Text(file.parentName ?: "Selected folder", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onDelete(file) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var generation by remember { mutableStateOf(0) }
    val sections = remember(generation) { DiagnosticsCollector.collect(context) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Handheld Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Information Android exposes about this handheld. Some manufacturer-specific sensors or values may not be available without privileged access.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { generation++ }) { Text("Refresh") }
        }
        sections.forEach { section ->
            item { Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        section.items.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.label, fontWeight = FontWeight.SemiBold)
                                Text(item.value)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatcherScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var patchUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Select a source ROM and an IPS, BPS, or UPS patch.") }
    var working by remember { mutableStateOf(false) }
    var pendingOutput by remember { mutableStateOf<ByteArray?>(null) }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingOutput
        if (uri != null && bytes != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    context.contentResolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output) { "Unable to open output file." }
                        output.write(bytes)
                    }
                }
                withContext(Dispatchers.Main) {
                    status = if (result.isSuccess) "Patched ROM saved successfully." else "Save failed: ${result.exceptionOrNull()?.message}"
                    pendingOutput = null
                }
            }
        } else {
            pendingOutput = null
        }
    }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sourceUri = it }
    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { patchUri = it }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline ROM Patcher", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Patching happens locally on the handheld. Nothing is uploaded. The source ROM is never overwritten.")
        }
        item {
            PickerCard("Source ROM", sourceUri?.let { displayName(context, it) } ?: "None selected") {
                sourcePicker.launch(arrayOf("*/*"))
            }
        }
        item {
            PickerCard("Patch file", patchUri?.let { displayName(context, it) } ?: "None selected") {
                patchPicker.launch(arrayOf("*/*"))
            }
        }
        item {
            Button(
                enabled = sourceUri != null && patchUri != null && !working,
                onClick = {
                    val source = sourceUri ?: return@Button
                    val patch = patchUri ?: return@Button
                    scope.launch {
                        working = true
                        status = "Verifying and applying patch…"
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val sourceBytes = readReasonableFile(context, source, "source ROM")
                                val patchBytes = readReasonableFile(context, patch, "patch")
                                RomPatcher.apply(sourceBytes, patchBytes)
                            }
                        }
                        working = false
                        result.onSuccess { patched ->
                            pendingOutput = patched.bytes
                            status = "${patched.format} patch applied. Choose where to save the new ROM."
                            val sourceName = displayName(context, source)
                            outputPicker.launch(suggestOutputName(sourceName))
                        }.onFailure {
                            status = "Patch failed: ${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                }
            ) { Text(if (working) "Working…" else "Apply patch") }
        }
        item { Text(status) }
        item {
            Text(
                "Initial version: IPS, BPS, and UPS. Very large disc-image/xdelta patching will be added separately with a streaming implementation.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PickerCard(title: String, value: String, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onPick) { Text("Choose") }
        }
    }
}

@Composable
private fun SummaryCard(title: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            lines.forEach { Text(it) }
        }
    }
}

@Composable
private fun FileRow(file: ScannedFile) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(file.name); Text(file.parentName ?: "", style = MaterialTheme.typography.bodySmall) }
            Text(DiagnosticsCollector.formatBytes(file.size))
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "Selected file"

private fun suggestOutputName(sourceName: String): String {
    val dot = sourceName.lastIndexOf('.')
    return if (dot > 0) sourceName.substring(0, dot) + "-patched" + sourceName.substring(dot) else "$sourceName-patched"
}

private fun readReasonableFile(context: Context, uri: Uri, label: String): ByteArray {
    val maxBytes = 192L * 1024L * 1024L
    val size = DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
    require(size <= maxBytes || size == 0L) {
        "The $label is larger than 192 MB. This first patcher uses an in-memory engine; large-file streaming support is planned."
    }
    return context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to read $label." }
        input.readBytes()
    }
}
