package com.astral.stitchapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ToneGenerator
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class PackagingOption {
    FOLDER, ZIP, PDF
}

data class Template(val name: String, val settings: JSONObject)

class MainActivity : ComponentActivity() {
    companion object {
        @JvmStatic
        fun convertWebpToPng(path: String): Boolean {
            val file = File(path)
            if (!file.exists()) return false
            return try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val pngFile = File(file.parent, file.nameWithoutExtension + ".png")
                    pngFile.outputStream().use { outs ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outs)
                    }
                    bitmap.recycle()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContent { MaterialTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val titles = listOf("Stitcher", "Rawloader")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("AstralStitch v1.2.0") })
                TabRow(selectedTabIndex = selectedTab) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                StitchTab()
            } else {
                BatoTab()
            }
        }
    }
}

// ==========================================
// SHARED SETTINGS & TEMPLATES
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchSettingsUI(
    splitHeight: String, onSplitH: (String)->Unit,
    outputType: String, onOutT: (String)->Unit,
    customFileName: String, onFileN: (String)->Unit,
    widthEnforce: Int, onWidthEn: (Int)->Unit,
    customWidth: String, onCustW: (String)->Unit,
    sensitivity: String, onSens: (String)->Unit,
    ignorable: String, onIgn: (String)->Unit,
    scanStep: String, onScan: (String)->Unit,
    packaging: PackagingOption, onPack: (PackagingOption)->Unit,
    // Template params
    currentTemplate: Template?,
    onLoadTemplate: (Template?) -> Unit,
    onSaveTemplate: (String) -> Unit,
    onDeleteTemplate: (Template) -> Unit
) {
    val context = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var newTemplateName by remember { mutableStateOf("") }
    var expandedTemplates by remember { mutableStateOf(false) }
    var availableTemplates by remember { mutableStateOf(listOf<Template>()) }

    // Load templates function
    fun loadTemplates() {
        val prefs = context.getSharedPreferences("stitch_templates", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", setOf()) ?: setOf()
        availableTemplates = set.map {
            val json = JSONObject(it)
            Template(json.getString("name"), json.getJSONObject("settings"))
        }.sortedBy { it.name }
    }

    LaunchedEffect(Unit) { loadTemplates() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Template Controls
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expandedTemplates = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(currentTemplate?.name ?: "Default Settings")
                }
                DropdownMenu(expanded = expandedTemplates, onDismissRequest = { expandedTemplates = false }) {
                    DropdownMenuItem(
                        text = { Text("Default Settings") },
                        onClick = { onLoadTemplate(null); expandedTemplates = false }
                    )
                    availableTemplates.forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(t.name)
                                    Text("X", color = Color.Red, modifier = Modifier.clickable {
                                        onDeleteTemplate(t)
                                        loadTemplates()
                                    })
                                }
                            },
                            onClick = { onLoadTemplate(t); expandedTemplates = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showSaveDialog = true }) { Text("Save") }
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Template") },
                text = { OutlinedTextField(value = newTemplateName, onValueChange = { newTemplateName = it }, label = { Text("Template Name") }) },
                confirmButton = {
                    Button(onClick = {
                        if (newTemplateName.isNotBlank()) {
                            onSaveTemplate(newTemplateName)
                            loadTemplates()
                            showSaveDialog = false
                            newTemplateName = ""
                        }
                    }) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }

        HorizontalDivider()

        OutlinedTextField(
            value = splitHeight,
            onValueChange = { onSplitH(it.filter { ch -> ch.isDigit() }) },
            label = { Text("Split Height") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Output")
            Spacer(Modifier.width(8.dp))
            listOf(".png", ".jpg").forEach { t ->
                FilterChip(selected = outputType == t, onClick = { onOutT(t) }, label = { Text(t) })
                Spacer(Modifier.width(8.dp))
            }
        }

        OutlinedTextField(
            value = customFileName,
            onValueChange = onFileN,
            label = { Text("Custom Filename ({num}.{ext})") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width: ")
            listOf(0, 1, 2).forEach { w ->
                FilterChip(selected = widthEnforce == w, onClick = { onWidthEn(w) }, label = { Text("$w") })
                Spacer(Modifier.width(4.dp))
            }
        }

        if (widthEnforce == 2) {
            OutlinedTextField(
                value = customWidth,
                onValueChange = { onCustW(it.filter { ch -> ch.isDigit() }) },
                label = { Text("Custom Width") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sensitivity,
                onValueChange = { onSens(it.filter { ch -> ch.isDigit() }) },
                label = { Text("Sens.") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ignorable,
                onValueChange = { onIgn(it.filter { ch -> ch.isDigit() }) },
                label = { Text("Ignore Px") },
                modifier = Modifier.weight(1f)
            )
             OutlinedTextField(
                value = scanStep,
                onValueChange = { onScan(it.filter { ch -> ch.isDigit() }) },
                label = { Text("Step") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pack:")
            Spacer(Modifier.width(4.dp))
            PackagingOption.values().forEach { opt ->
                FilterChip(
                    selected = packaging == opt,
                    onClick = { onPack(opt) },
                    label = { Text(opt.name) }
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

// ==========================================
// TAB 1: STITCHER (Queue System)
// ==========================================

data class LocalQueueItem(
    val id: String,
    val path: String,
    val name: String,
    val status: String, // pending, processing, done, failed
    val progress: Double
)

data class QueueItem(
    val id: String,
    val url: String,
    val title: String,
    val status: String, // pending, downloading, stitching, done, failed, paused
    val progress: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Queue State
    var queueItems by remember { mutableStateOf(listOf<LocalQueueItem>()) }
    val queueMutex = remember { Mutex() }
    var isProcessing by remember { mutableStateOf(false) }
    var concurrencyCount by remember { mutableIntStateOf(1) }
    var expandedConcurrency by remember { mutableStateOf(false) }

    // Settings State
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var customFileName by remember { mutableStateOf("") }
    var widthEnforce by remember { mutableIntStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }

    // Template State
    var currentTemplate by remember { mutableStateOf<Template?>(null) }

    // Notification State
    var showNotification by remember { mutableStateOf(false) }
    var notificationMessage by remember { mutableStateOf("") }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null) {
                // Add to queue
                val newItem = LocalQueueItem(
                    id = java.util.UUID.randomUUID().toString(),
                    path = uri.toString(),
                    name = doc.name ?: "Unknown",
                    status = "pending",
                    progress = 0.0
                )
                scope.launch {
                    queueMutex.withLock {
                        queueItems = queueItems + newItem
                    }
                }
            }
        }
    }

    // Helper to save/load templates
    fun saveTemplate(name: String) {
        val settings = JSONObject().apply {
            put("splitHeight", splitHeight)
            put("outputType", outputType)
            put("customFileName", customFileName)
            put("widthEnforce", widthEnforce)
            put("customWidth", customWidth)
            put("sensitivity", sensitivity)
            put("ignorable", ignorable)
            put("scanStep", scanStep)
            put("packaging", packagingOption.name)
        }
        val tObj = JSONObject().apply {
            put("name", name)
            put("settings", settings)
        }
        val prefs = context.getSharedPreferences("stitch_templates", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", mutableSetOf())!!.toMutableSet()
        // Remove existing with same name if any
        set.removeIf { JSONObject(it).getString("name") == name }
        set.add(tObj.toString())
        prefs.edit().putStringSet("templates", set).apply()
        currentTemplate = Template(name, settings)
    }

    fun deleteTemplate(t: Template) {
        val prefs = context.getSharedPreferences("stitch_templates", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", mutableSetOf())!!.toMutableSet()
        set.removeIf { JSONObject(it).getString("name") == t.name }
        prefs.edit().putStringSet("templates", set).apply()
        if (currentTemplate?.name == t.name) currentTemplate = null
    }

    fun applyTemplate(t: Template?) {
        currentTemplate = t
        if (t != null) {
            val s = t.settings
            splitHeight = s.optString("splitHeight", "5000")
            outputType = s.optString("outputType", ".png")
            customFileName = s.optString("customFileName", "")
            widthEnforce = s.optInt("widthEnforce", 0)
            customWidth = s.optString("customWidth", "720")
            sensitivity = s.optString("sensitivity", "90")
            ignorable = s.optString("ignorable", "0")
            scanStep = s.optString("scanStep", "5")
            packagingOption = try { PackagingOption.valueOf(s.optString("packaging", "FOLDER")) } catch(e:Exception) { PackagingOption.FOLDER }
        } else {
            // Defaults
            splitHeight = "5000"
            outputType = ".png"
            customFileName = ""
            widthEnforce = 0
            customWidth = "720"
            sensitivity = "90"
            ignorable = "0"
            scanStep = "5"
            packagingOption = PackagingOption.FOLDER
        }
    }

    // Queue Processor
    LaunchedEffect(isProcessing, concurrencyCount) {
        if (!isProcessing) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            val jobs = List(concurrencyCount) {
                launch {
                    while (isActive && isProcessing) {
                        var itemToProcess: LocalQueueItem? = null

                        queueMutex.withLock {
                             val pending = queueItems.find { it.status == "pending" }
                             if (pending != null) {
                                 itemToProcess = pending
                                 queueItems = queueItems.map { if (it.id == pending.id) it.copy(status = "processing") else it }
                             }
                        }

                        if (itemToProcess == null) {
                            // Check if all done
                            var allDone = false
                            queueMutex.withLock {
                                if (queueItems.none { it.status == "processing" } && queueItems.isNotEmpty() && queueItems.all { it.status == "done" || it.status == "failed" }) {
                                    allDone = true
                                }
                            }
                            if (allDone) {
                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                    showNotification = true
                                    notificationMessage = "Stitching Complete!"
                                    val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
                                }
                            }
                            delay(1000)
                            continue
                        }

                        val item = itemToProcess!!
                        try {
                            val inputUri = Uri.parse(item.path)

                            // 1. Copy Input
                            val cacheIn = File(context.cacheDir, "stitch_in_${item.id}")
                            cacheIn.deleteRecursively(); cacheIn.mkdirs()
                            copyFromTree(context, inputUri, cacheIn)

                            // 2. Prepare Output
                            val cacheOutParent = File(context.cacheDir, "stitch_out_${item.id}")
                            val outputName = "${item.name} [Stitched]"
                            cacheOutParent.deleteRecursively(); cacheOutParent.mkdirs()
                            val dir = File(cacheOutParent, outputName)
                            dir.mkdirs()

                            // 3. Run Python
                            val py = Python.getInstance()
                            val bridge = py.getModule("bridge")
                            val progressFile = File(context.cacheDir, "prog_${item.id}.json")

                            // Monitor progress (optional, simplistic here)
                            val monitor = launch {
                                while(isActive) {
                                    if(progressFile.exists()) {
                                        try {
                                            val j = JSONObject(progressFile.readText())
                                            val p = j.optInt("processed", 0)
                                            val t = j.optInt("total", 1)
                                            val prog = if(t>0) p.toDouble()/t else 0.0
                                             withContext(Dispatchers.Main) {
                                                queueItems = queueItems.map { if (it.id == item.id) it.copy(progress = prog) else it }
                                             }
                                        } catch(_:Exception){}
                                    }
                                    delay(500)
                                }
                            }

                            val finalPathStr = bridge.callAttr(
                                "run", cacheIn.absolutePath,
                                splitHeight.toIntOrNull()?:5000,
                                outputType, false, widthEnforce,
                                customWidth.toIntOrNull()?:720,
                                sensitivity.toIntOrNull()?:90,
                                ignorable.toIntOrNull()?:0,
                                scanStep.toIntOrNull()?:5,
                                false, 20, // Low RAM disabled
                                dir.absolutePath,
                                customFileName.takeIf { it.isNotBlank() },
                                packagingOption == PackagingOption.ZIP,
                                packagingOption == PackagingOption.PDF,
                                progressFile.absolutePath, 0, true
                            ).toString()

                            monitor.cancel()

                            // 4. Copy Back (Next to input)
                            val finalFile = File(finalPathStr)
                            val targetTree = DocumentFile.fromTreeUri(context, inputUri)

                            if (targetTree != null && targetTree.canWrite()) {
                                 if (finalFile.isDirectory) {
                                    copyToTree(context, finalFile, targetTree)
                                } else {
                                    val mime = if(finalFile.extension == "pdf") "application/pdf" else "application/zip"
                                    copyToTree(context, finalFile, targetTree, mime)
                                }
                            }

                            // Cleanup
                            cacheIn.deleteRecursively()
                            cacheOutParent.deleteRecursively()
                            progressFile.delete()

                            withContext(Dispatchers.Main) {
                                queueItems = queueItems.map { if (it.id == item.id) it.copy(status = "done", progress = 1.0) else it }
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                             withContext(Dispatchers.Main) {
                                queueMutex.withLock {
                                    queueItems = queueItems.map { if (it.id == item.id) it.copy(status = "failed", progress = 0.0) else it }
                                }
                            }
                        }
                    }
                }
            }
            jobs.joinAll()
        }
    }

    if (showNotification) {
        AlertDialog(
            onDismissRequest = { showNotification = false },
            confirmButton = { Button(onClick = { showNotification = false }) { Text("OK") } },
            title = { Text("Notification") },
            text = { Text(notificationMessage) }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manual Stitcher (Local Queue)", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickInput.launch(null) }) { Text("Add Folder to Queue") }
             Spacer(Modifier.width(8.dp))
             // Text field that overflows
             Text(
                 text = "Queue: ${queueItems.size} items",
                 modifier = Modifier.weight(1f),
                 overflow = TextOverflow.Ellipsis,
                 maxLines = 2
             )
        }

        HorizontalDivider()

        StitchSettingsUI(
            splitHeight, { splitHeight = it },
            outputType, { outputType = it },
            customFileName, { customFileName = it },
            widthEnforce, { widthEnforce = it },
            customWidth, { customWidth = it },
            sensitivity, { sensitivity = it },
            ignorable, { ignorable = it },
            scanStep, { scanStep = it },
            packagingOption, { packagingOption = it },
            currentTemplate, ::applyTemplate, ::saveTemplate, ::deleteTemplate
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
             Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { expandedConcurrency = true }) {
                        Text("Workers: $concurrencyCount")
                    }
                    DropdownMenu(expanded = expandedConcurrency, onDismissRequest = { expandedConcurrency = false }) {
                        (1..5).forEach { num ->
                            DropdownMenuItem(
                                text = { Text("$num") },
                                onClick = { concurrencyCount = num; expandedConcurrency = false }
                            )
                        }
                    }
                }
            }
             Button(onClick = { scope.launch { queueMutex.withLock { queueItems = listOf(); isProcessing = false } } }) { Text("Clear All") }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { isProcessing = !isProcessing },
            colors = ButtonDefaults.buttonColors(containerColor = if(isProcessing) Color.Red else Color(0xFF4CAF50))
        ) { Text(if (isProcessing) "STOP QUEUE" else "START QUEUE") }

        LazyColumn(
            modifier = Modifier.height(400.dp).fillMaxWidth().background(Color(0xFFEEEEEE)),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(queueItems) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines=1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { scope.launch { queueMutex.withLock { queueItems = queueItems.filter { it.id != item.id } } } }) {
                                Text("X")
                            }
                         }
                         Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                             Text(item.status.uppercase(), style = MaterialTheme.typography.labelSmall,
                                 color = if (item.status == "failed") Color.Red else if(item.status=="done") Color.Green else Color.Unspecified)
                         }
                         if (item.status == "processing") {
                             LinearProgressIndicator(progress = item.progress.toFloat(), modifier = Modifier.fillMaxWidth())
                         }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 2: BATO / RIDI DOWNLOADER
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatoTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSource by remember { mutableStateOf("Bato.to") } // Bato.to or Ridibooks
    var expandedSource by remember { mutableStateOf(false) }

    var urlInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    var autoRetry by remember { mutableStateOf(true) }

    var queueItems by remember { mutableStateOf(listOf<QueueItem>()) }
    var isProcessorRunning by remember { mutableStateOf(false) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var concurrencyCount by remember { mutableIntStateOf(1) }
    var expandedConcurrency by remember { mutableStateOf(false) }
    var isAddingToQueue by remember { mutableStateOf(false) }

    // Settings State (Shared)
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var customFileName by remember { mutableStateOf("") }
    var widthEnforce by remember { mutableIntStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }

    // Notification State
    var showNotification by remember { mutableStateOf(false) }

    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = it
        }
    }

    // Load Queue Poller
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while(isActive) {
                if (!Python.isStarted()) { delay(500); continue }
                val py = Python.getInstance()
                val bato = py.getModule("bato")
                try {
                    val jsonStr = bato.callAttr("get_queue", context.cacheDir.absolutePath).toString()
                    val jsonArr = JSONArray(jsonStr)
                    val list = mutableListOf<QueueItem>()
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        list.add(QueueItem(
                            id = obj.getString("id"),
                            url = obj.getString("url"),
                            title = obj.getString("title"),
                            status = obj.getString("status"),
                            progress = obj.optDouble("progress", 0.0)
                        ))
                    }
                    // Check completion for notification
                    if (list.isNotEmpty() && list.all { it.status == "done" || it.status == "failed" } && isProcessorRunning) {
                         // Only notify if we were running and now all done.
                         // But we need to detect edge trigger. Too complex for simple poll loop without state.
                         // Simplified: Processor worker will trigger notify.
                    }
                    withContext(Dispatchers.Main) {
                        queueItems = list
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(1000)
            }
        }
    }

    // Queue Processor
    LaunchedEffect(isProcessorRunning, concurrencyCount) {
        if (!isProcessorRunning) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            val jobs = List(concurrencyCount) {
                launch {
                    while (isActive && isProcessorRunning) {
                        if (outputUri == null) { delay(1000); continue }

                        // Check if all done
                        val pendingCount = queueItems.count { it.status == "pending" || it.status == "downloading" || it.status == "stitching" || it.status == "initializing" }
                        if (pendingCount == 0 && queueItems.isNotEmpty()) {
                             withContext(Dispatchers.Main) {
                                isProcessorRunning = false
                                showNotification = true
                                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
                            }
                            break
                        }

                        try {
                            val py = Python.getInstance()
                            val bato = py.getModule("bato")

                            val params = JSONObject().apply {
                                put("splitHeight", splitHeight)
                                put("outputType", outputType)
                                put("packaging", packagingOption.name)
                                put("widthEnforce", widthEnforce)
                                put("customWidth", customWidth)
                                put("sensitivity", sensitivity)
                                put("ignorable", ignorable)
                                put("scanStep", scanStep)
                                put("autoRetry", autoRetry)
                            }

                            val resultStr = bato.callAttr("process_next_item", context.cacheDir.absolutePath, params.toString()).toString()
                            val result = JSONObject(resultStr)

                            if (result.has("status")) {
                                val status = result.getString("status")
                                if (status == "empty") {
                                    delay(2000)
                                } else if (status == "success") {
                                    val path = result.getString("path")
                                    val file = File(path)
                                    val targetTree = DocumentFile.fromTreeUri(context, outputUri!!)
                                    if (targetTree != null && file.exists()) {
                                        if (file.isDirectory) {
                                            copyToTree(context, file, targetTree)
                                        } else {
                                            val mime = if(file.extension == "pdf") "application/pdf" else "application/zip"
                                            copyToTree(context, file, targetTree, mime)
                                        }
                                    }
                                } else {
                                    delay(1000)
                                }
                            } else if (result.has("error")) {
                                 delay(1000)
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                            delay(2000)
                        }
                    }
                }
            }
            jobs.joinAll()
        }
    }

    if (showNotification) {
        AlertDialog(
            onDismissRequest = { showNotification = false },
            confirmButton = { Button(onClick = { showNotification = false }) { Text("OK") } },
            title = { Text("Notification") },
            text = { Text("All tasks completed!") }
        )
    }

    // Define QueueItemRow for BatoTab
    @Composable
    fun QueueItemRow(item: QueueItem, onDelete: (String) -> Unit, onRetry: (String) -> Unit, onPause: (String) -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(item.status.uppercase(), style = MaterialTheme.typography.labelSmall,
                         color = if (item.status == "failed") Color.Red else Color.Unspecified)

                    Row {
                        if (item.status == "downloading" || item.status == "stitching") {
                            Button(onClick = { onPause(item.id) }, modifier = Modifier.height(32.dp)) {
                                Text("Pause", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        if (item.status == "paused") {
                            Button(onClick = { onRetry(item.id) }, modifier = Modifier.height(32.dp)) {
                                Text("Resume", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        if (item.status == "failed") {
                            Button(onClick = { onRetry(item.id) }, modifier = Modifier.height(32.dp)) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        // Always allow removing (which acts as Cancel for downloading items)
                        IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) {
                            Text("X")
                        }
                    }
                }
                if (item.status == "downloading" || item.status == "stitching") {
                    LinearProgressIndicator(progress = item.progress.toFloat(), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bulk Downloader", style = MaterialTheme.typography.titleMedium)

        // Source Selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Source: ")
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { expandedSource = true }) {
                    Text(selectedSource)
                }
                DropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                    DropdownMenuItem(text = { Text("Bato.to") }, onClick = { selectedSource = "Bato.to"; expandedSource = false })
                    DropdownMenuItem(text = { Text("Ridibooks") }, onClick = { selectedSource = "Ridibooks"; expandedSource = false })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = {
                    Text(if (selectedSource == "Ridibooks") "Url (Book)" else "Url (Chapter/Series)")
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (isAddingToQueue) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Button(
                    onClick = {
                        isAddingToQueue = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val py = Python.getInstance()
                                val bato = py.getModule("bato")
                                val type = if(selectedSource == "Ridibooks") "ridi" else "bato"
                                bato.callAttr("add_to_queue", context.cacheDir.absolutePath, urlInput, type, cookieInput)
                                withContext(Dispatchers.Main) {
                                    urlInput = ""
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    isAddingToQueue = false
                                }
                            }
                        }
                    },
                    enabled = urlInput.isNotBlank()
                ) { Text("Add") }
            }
        }

        if (selectedSource == "Ridibooks") {
             OutlinedTextField(
                value = cookieInput,
                onValueChange = { cookieInput = it },
                label = { Text("Cookie (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickOutput.launch(null) }) { Text("Set Output Folder") }
            Spacer(Modifier.width(8.dp))
            Text(outputUri?.lastPathSegment ?: "Not Set", maxLines=1, overflow = TextOverflow.Ellipsis)
        }

        // Auto Retry
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoRetry, onCheckedChange = { autoRetry = it })
            Text("Auto Retry")
        }

        // Full Settings for Bato
        Text("Stitch Settings for Queue", style = MaterialTheme.typography.bodySmall)
        // Note: Template system not explicitly requested for Bato tab, but settings are here.
        // Can reuse StitchSettingsUI but without templates if desired, or with.
        // Prompt says "Tambahkan fitur template ke manual stitcher" (Add template feature to manual stitcher).
        // It doesn't explicitly say Bato. But usually settings are shared.
        // I'll stick to basic settings UI here for simplicity or reuse without template controls to match prompt strictly?
        // Let's just use the shared UI but pass null/noop for templates to keep Bato tab clean as requested (only stitcher mentioned).
        StitchSettingsUI(
            splitHeight, { splitHeight = it },
            outputType, { outputType = it },
            customFileName, { customFileName = it },
            widthEnforce, { widthEnforce = it },
            customWidth, { customWidth = it },
            sensitivity, { sensitivity = it },
            ignorable, { ignorable = it },
            scanStep, { scanStep = it },
            packagingOption, { packagingOption = it },
            null, {}, {}, {}
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Queue (${queueItems.size})")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { expandedConcurrency = true }) {
                        Text("Workers: $concurrencyCount")
                    }
                    DropdownMenu(expanded = expandedConcurrency, onDismissRequest = { expandedConcurrency = false }) {
                        (1..5).forEach { num ->
                            DropdownMenuItem(
                                text = { Text("$num") },
                                onClick = { concurrencyCount = num; expandedConcurrency = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                 Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                             Python.getInstance().getModule("bato").callAttr("clear_completed", context.cacheDir.absolutePath)
                        }
                    }
                ) { Text("Clear Done") }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { isProcessorRunning = !isProcessorRunning },
            colors = ButtonDefaults.buttonColors(containerColor = if(isProcessorRunning) Color.Red else Color(0xFF4CAF50))
        ) { Text(if (isProcessorRunning) "STOP QUEUE" else "START QUEUE") }

        if (isProcessorRunning && outputUri == null) {
            Text("Please select Output Folder to start processing!", color = Color.Red)
        }

        LazyColumn(
            modifier = Modifier.height(400.dp).fillMaxWidth().background(Color(0xFFEEEEEE)),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(queueItems) { item ->
                QueueItemRow(
                    item = item,
                    onDelete = { id ->
                        scope.launch(Dispatchers.IO) {
                            Python.getInstance().getModule("bato").callAttr("remove_from_queue", context.cacheDir.absolutePath, id)
                        }
                    },
                    onRetry = { id ->
                        scope.launch(Dispatchers.IO) {
                            Python.getInstance().getModule("bato").callAttr("retry_item", context.cacheDir.absolutePath, id)
                        }
                    },
                    onPause = { id ->
                        scope.launch(Dispatchers.IO) {
                            Python.getInstance().getModule("bato").callAttr("pause_item", context.cacheDir.absolutePath, id)
                        }
                    }
                )
            }
        }
    }
}

// ==========================================
// HELPERS
// ==========================================

fun copyFromTree(ctx: android.content.Context, treeUri: Uri, dest: java.io.File) {
    val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return

    fun copy(doc: DocumentFile, base: java.io.File, isRoot: Boolean) {
        if (doc.isDirectory) {
            if (isRoot) {
                for (child in doc.listFiles()) copy(child, base, false)
            } else {
                 // For subdirectories, replicate structure?
                 // The stitcher usually flattens or recurses?
                 // Original implementation: copy(child, base, false) -> flattens into base.
                 // Let's keep it flattening for now as SmartStitchCore expects a folder of images.
                 for (child in doc.listFiles()) copy(child, base, false)
            }
            return
        }

        val name = doc.name ?: return
        val isImage = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
            "png", "jpg", "jpeg", "webp", "bmp", "tiff", "tif", "tga"
        )
        if (!isImage) return

        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".webp")) {
            val baseName = name.dropLast(5)
            val targetName = "$baseName.png"
            var targetFile = java.io.File(base, targetName)
            var index = 1
            while (targetFile.exists()) {
                targetFile = java.io.File(base, "${baseName}_webp$index.png")
                index += 1
            }
            val converted = ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                BitmapFactory.decodeStream(ins)
            }
            if (converted != null) {
                targetFile.outputStream().use { outs ->
                    converted.compress(Bitmap.CompressFormat.PNG, 100, outs)
                }
                converted.recycle()
            } else {
                 val fallbackFile = java.io.File(base, name)
                ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    fallbackFile.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        } else {
            val outFile = java.io.File(base, name)
            ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                outFile.outputStream().use { outs -> ins.copyTo(outs) }
            }
        }
    }
    copy(root, dest, true)
}

fun copyToTree(
    ctx: android.content.Context,
    src: java.io.File,
    destTree: DocumentFile?,
    mimeOverride: String? = null
) {
    if (destTree == null) return

    fun inferredMime(file: java.io.File): String {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "png", "jpg", "jpeg", "webp", "bmp", "tiff", "tif", "tga" -> "image/*"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    fun upload(file: java.io.File, parent: DocumentFile, overrideMime: String? = null) {
        if (file.isDirectory) {
            val dir = parent.findFile(file.name)?.takeIf { it.isDirectory } ?: parent.createDirectory(file.name)!!
            file.listFiles()?.forEach { child -> upload(child, dir, null) }
        } else {
            val mime = overrideMime ?: inferredMime(file)
            val existing = parent.findFile(file.name)?.let { current ->
                if (current.isDirectory) {
                    current.delete()
                    null
                } else {
                    current
                }
            }
            // If exists, delete or overwrite?
            // SAF create file might generate "name (1).ext" automatically if exists? No, it usually fails or duplicates.
            // Let's delete existing to overwrite.
            if (existing != null) existing.delete()

            val target = parent.createFile(mime, file.name)!!
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { outs ->
                file.inputStream().use { ins -> ins.copyTo(outs) }
            }
        }
    }
    upload(src, destTree, mimeOverride)
}
