package com.astral.stitchapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

enum class PackagingOption {
    FOLDER, ZIP, PDF
}

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
    val titles = listOf("Stitcher", "Bato Downloader")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("AstralStitch v1.1.5") })
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
// TAB 1: STITCHER (Legacy + Refined)
// ==========================================

@Composable
fun StitchTab() {
    val context = LocalContext.current
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }

    // Stitch Settings
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var customFileName by remember { mutableStateOf("") }
    var batchMode by remember { mutableStateOf(false) }
    var lowRam by remember { mutableStateOf(false) }
    var unitImages by remember { mutableStateOf("20") }
    var widthEnforce by remember { mutableIntStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }

    var logText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var progressTarget by remember { mutableFloatStateOf(0f) }
    var progressVisible by remember { mutableStateOf(false) }

    val animatedProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(progressTarget) {
        val clamped = progressTarget.coerceIn(0f, 1f)
        animatedProgress.animateTo(clamped, animationSpec = tween(500))
    }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) {
             context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
             inputUri = it
        }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = it
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manual Stitcher (Local Files)", style = MaterialTheme.typography.titleMedium)

        // Input/Output Pickers
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickInput.launch(null) }) { Text("Input Folder") }
            Spacer(Modifier.width(8.dp))
            Text(inputUri?.lastPathSegment ?: "None", maxLines=1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickOutput.launch(null) }) { Text("Output Folder") }
            Spacer(Modifier.width(8.dp))
            Text(outputUri?.lastPathSegment ?: "None", maxLines=1)
        }

        HorizontalDivider()

        // Settings UI Shared
        StitchSettingsUI(
            splitHeight, { splitHeight = it },
            outputType, { outputType = it },
            customFileName, { customFileName = it },
            batchMode, { batchMode = it },
            lowRam, { lowRam = it },
            unitImages, { unitImages = it },
            widthEnforce, { widthEnforce = it },
            customWidth, { customWidth = it },
            sensitivity, { sensitivity = it },
            ignorable, { ignorable = it },
            scanStep, { scanStep = it },
            packagingOption, { packagingOption = it }
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning && inputUri != null,
            onClick = {
                isRunning = true
                progressVisible = true
                progressTarget = 0f
                logText = "Starting..."

                scope.launch {
                    try {
                        // 1. Copy Input
                        logText += "\nCopying input..."
                        val cacheIn = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "stitch_in")
                            dir.deleteRecursively(); dir.mkdirs()
                            copyFromTree(context, inputUri!!, dir)
                            dir.absolutePath
                        }

                        // 2. Prepare Output Cache
                        val cacheOutParent = File(context.cacheDir, "stitch_out")
                        val inputName = DocumentFile.fromTreeUri(context, inputUri!!)?.name ?: "output"
                        val outputName = "$inputName [Stitched]"
                        val cacheOut = withContext(Dispatchers.IO) {
                            cacheOutParent.deleteRecursively(); cacheOutParent.mkdirs()
                            val dir = File(cacheOutParent, outputName)
                            dir.mkdirs()
                            dir.absolutePath
                        }

                        // 3. Run Python
                        logText += "\nStitching..."
                        withContext(Dispatchers.Default) {
                            val py = Python.getInstance()
                            val bridge = py.getModule("bridge")
                            val progressFile = File(context.cacheDir, "prog_stitch.json")

                            // Progress Monitor Job
                            val monitor = launch {
                                while(isActive) {
                                    if(progressFile.exists()) {
                                        try {
                                            val j = JSONObject(progressFile.readText())
                                            val p = j.optInt("processed", 0)
                                            val t = j.optInt("total", 1)
                                            if (t > 0) progressTarget = p.toFloat() / t
                                        } catch(_:Exception){}
                                    }
                                    delay(200)
                                }
                            }

                            try {
                                bridge.callAttr(
                                    "run", cacheIn,
                                    splitHeight.toIntOrNull()?:5000,
                                    outputType, batchMode, widthEnforce,
                                    customWidth.toIntOrNull()?:720,
                                    sensitivity.toIntOrNull()?:90,
                                    ignorable.toIntOrNull()?:0,
                                    scanStep.toIntOrNull()?:5,
                                    lowRam, unitImages.toIntOrNull()?:20,
                                    cacheOut,
                                    customFileName.takeIf { it.isNotBlank() },
                                    packagingOption == PackagingOption.ZIP,
                                    packagingOption == PackagingOption.PDF,
                                    progressFile.absolutePath, 0, true
                                )
                            } finally {
                                monitor.cancel()
                                progressTarget = 1f
                            }
                        }

                        // 4. Copy Back
                        logText += "\nSaving output..."
                        withContext(Dispatchers.IO) {
                            val targetTree = if (outputUri != null) DocumentFile.fromTreeUri(context, outputUri!!) else DocumentFile.fromTreeUri(context, inputUri!!)
                            if (targetTree != null) {
                                when(packagingOption) {
                                    PackagingOption.ZIP -> copyToTree(context, File("$cacheOut.zip"), targetTree, "application/zip")
                                    PackagingOption.PDF -> copyToTree(context, File("$cacheOut.pdf"), targetTree, "application/pdf")
                                    PackagingOption.FOLDER -> copyToTree(context, File(cacheOut), targetTree)
                                }
                            }
                        }
                        logText += "\nDone!"
                    } catch (e: Exception) {
                        logText += "\nError: ${e.message}"
                        e.printStackTrace()
                    } finally {
                        isRunning = false
                    }
                }
            }
        ) { Text(if(isRunning) "Processing..." else "Start Stitching") }

        if (progressVisible) {
            LinearProgressIndicator(progress = animatedProgress.value, modifier = Modifier.fillMaxWidth())
        }
        Text(logText, style = MaterialTheme.typography.bodySmall)
    }
}

// ==========================================
// TAB 2: BATO DOWNLOADER (Queue System)
// ==========================================

data class QueueItem(
    val id: String,
    val url: String,
    val title: String,
    val status: String, // pending, downloading, stitching, done, failed
    val progress: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatoTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("") }
    var queueItems by remember { mutableStateOf(listOf<QueueItem>()) }
    var isProcessorRunning by remember { mutableStateOf(false) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var concurrencyCount by remember { mutableIntStateOf(1) }
    var expandedConcurrency by remember { mutableStateOf(false) }

    // Stitch Settings (Duplicated State)
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var customFileName by remember { mutableStateOf("") }
    var batchMode by remember { mutableStateOf(false) }
    var lowRam by remember { mutableStateOf(false) }
    var unitImages by remember { mutableStateOf("20") }
    var widthEnforce by remember { mutableIntStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }

    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = it
        }
    }

    // Load Queue Poller
    LaunchedEffect(Unit) {
        while(isActive) {
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
                queueItems = list
            } catch (e: Exception) { e.printStackTrace() }
            delay(1000)
        }
    }

    // Queue Processor Background Worker(s)
    LaunchedEffect(isProcessorRunning, concurrencyCount) {
        if (!isProcessorRunning) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            // Launch N workers
            val jobs = List(concurrencyCount) {
                launch {
                    while (isActive && isProcessorRunning) {
                        // Check if we have an output destination
                        if (outputUri == null) {
                            delay(1000)
                            continue
                        }

                        try {
                            val py = Python.getInstance()
                            val bato = py.getModule("bato")

                            // Prepare params
                            val params = JSONObject().apply {
                                put("splitHeight", splitHeight)
                                put("outputType", outputType)
                                put("packaging", packagingOption.name)
                                put("widthEnforce", widthEnforce)
                                put("customWidth", customWidth)
                                put("sensitivity", sensitivity)
                                put("ignorable", ignorable)
                                put("scanStep", scanStep)
                                put("lowRam", lowRam)
                                put("unitImages", unitImages)
                            }

                            val resultStr = bato.callAttr("process_next_item", context.cacheDir.absolutePath, params.toString()).toString()
                            val result = JSONObject(resultStr)

                            if (result.has("status")) {
                                val status = result.getString("status")
                                if (status == "empty") {
                                    delay(2000)
                                } else if (status == "success") {
                                    // Copy to SAF
                                    val path = result.getString("path")
                                    val file = File(path)
                                    val targetTree = DocumentFile.fromTreeUri(context, outputUri!!)
                                    if (targetTree != null && file.exists()) {
                                        if (file.isDirectory) {
                                            copyToTree(context, file, targetTree)
                                        } else {
                                            // zip or pdf
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
            // Wait for all jobs to complete (which happens when cancelled)
            jobs.joinAll()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bato Bulk Downloader", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("Url (Chapter or Series)") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val py = Python.getInstance()
                    val bato = py.getModule("bato")
                    bato.callAttr("add_to_queue", context.cacheDir.absolutePath, urlInput)
                    urlInput = ""
                }
            },
            enabled = urlInput.isNotBlank()
        ) { Text("Add to Queue") }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickOutput.launch(null) }) { Text("Set Output Folder") }
            Spacer(Modifier.width(8.dp))
            Text(outputUri?.lastPathSegment ?: "Not Set", maxLines=1)
        }

        // Full Settings for Bato
        Text("Stitch Settings for Queue", style = MaterialTheme.typography.bodySmall)
        StitchSettingsUI(
            splitHeight, { splitHeight = it },
            outputType, { outputType = it },
            customFileName, { customFileName = it },
            batchMode, { batchMode = it },
            lowRam, { lowRam = it },
            unitImages, { unitImages = it },
            widthEnforce, { widthEnforce = it },
            customWidth, { customWidth = it },
            sensitivity, { sensitivity = it },
            ignorable, { ignorable = it },
            scanStep, { scanStep = it },
            packagingOption, { packagingOption = it }
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Queue (${queueItems.size})")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Concurrency Dropdown
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
                    }
                )
            }
        }
    }
}

@Composable
fun QueueItemRow(item: QueueItem, onDelete: (String) -> Unit, onRetry: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.status.uppercase(), style = MaterialTheme.typography.labelSmall,
                     color = if (item.status == "failed") Color.Red else Color.Unspecified)

                Row {
                    if (item.status == "failed") {
                        Button(onClick = { onRetry(item.id) }, modifier = Modifier.height(32.dp)) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (item.status == "failed" || item.status == "pending" || item.status == "done") {
                        IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) {
                            Text("X")
                        }
                    }
                }
            }
            if (item.status == "downloading" || item.status == "stitching") {
                LinearProgressIndicator(progress = item.progress.toFloat(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchSettingsUI(
    splitHeight: String, onSplitH: (String)->Unit,
    outputType: String, onOutT: (String)->Unit,
    customFileName: String, onFileN: (String)->Unit,
    batchMode: Boolean, onBatch: (Boolean)->Unit,
    lowRam: Boolean, onLowRam: (Boolean)->Unit,
    unitImages: String, onUnit: (String)->Unit,
    widthEnforce: Int, onWidthEn: (Int)->Unit,
    customWidth: String, onCustW: (String)->Unit,
    sensitivity: String, onSens: (String)->Unit,
    ignorable: String, onIgn: (String)->Unit,
    scanStep: String, onScan: (String)->Unit,
    packaging: PackagingOption, onPack: (PackagingOption)->Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            label = { Text("Custom Filename ({num})") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = batchMode, onClick = { onBatch(!batchMode) }, label = { Text("Batch Mode") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = lowRam, onClick = { onLowRam(!lowRam) }, label = { Text("Low RAM") })
        }

        if (lowRam) {
            OutlinedTextField(
                value = unitImages,
                onValueChange = { onUnit(it.filter { ch -> ch.isDigit() }) },
                label = { Text("Unit Images") }
            )
        }

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
// HELPERS (Duplicated/Shared from original)
// ==========================================

fun copyFromTree(ctx: android.content.Context, treeUri: Uri, dest: java.io.File) {
    val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return

    fun copy(doc: DocumentFile, base: java.io.File, isRoot: Boolean) {
        if (doc.isDirectory) {
            if (isRoot) {
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
            val target = existing ?: parent.createFile(mime, file.name)!!
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { outs ->
                file.inputStream().use { ins -> ins.copyTo(outs) }
            }
        }
    }
    upload(src, destTree, mimeOverride)
}
