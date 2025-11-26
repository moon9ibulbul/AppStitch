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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

enum class PackagingOption {
    FOLDER, ZIP, PDF
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { StitchScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchScreen() {
    val context = LocalContext.current

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var batchMode by remember { mutableStateOf(false) }
    var lowRam by remember { mutableStateOf(false) }
    var unitImages by remember { mutableStateOf("20") }
    var widthEnforce by remember { mutableStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }
    var batoUrl by remember { mutableStateOf("") }

    var logText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var progressTarget by remember { mutableFloatStateOf(0f) }
    var progressVisible by remember { mutableStateOf(false) }

    val animatedProgress = remember { Animatable(0f) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(progressTarget) {
        val clampedTarget = progressTarget.coerceIn(0f, 1f)
        val distance = (clampedTarget - animatedProgress.value).absoluteValue
        val duration = (distance * 800).roundToInt().coerceIn(120, 900)
        animatedProgress.animateTo(
            clampedTarget,
            animationSpec = tween(durationMillis = duration)
        )
    }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            inputUri = uri
        }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            outputUri = uri
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("AstralStitch") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Pilih folder sumber (INPUT) & tujuan (OUTPUT), atau masukkan URL chapter Bato.to untuk diunduh otomatis.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickInput.launch(null) }) {
                    Text(if (inputUri == null) "Pilih Folder INPUT" else "Ganti Folder INPUT")
                }
                Spacer(Modifier.width(8.dp))
                Text(inputUri?.lastPathSegment ?: "Belum dipilih")
            }
            OutlinedTextField(
                value = batoUrl,
                onValueChange = { batoUrl = it },
                label = { Text("URL Bato.to (opsional)") },
                supportingText = { Text("Jika terisi, aplikasi akan mengunduh gambar dari URL tersebut (pilih folder OUTPUT untuk menyimpan hasil).") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickOutput.launch(null) }) {
                    Text(if (outputUri == null) "Pilih Folder OUTPUT" else "Ganti Folder OUTPUT")
                }
                Spacer(Modifier.width(8.dp))
                Text(outputUri?.lastPathSegment ?: "Belum dipilih")
            }

            // ------ Fields angka difilter digit ------
            OutlinedTextField(
                value = splitHeight,
                onValueChange = { splitHeight = it.filter { ch -> ch.isDigit() } },
                label = { Text("Split Height") }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output Type")
                Spacer(Modifier.width(8.dp))
                listOf(".png", ".jpg").forEach { t ->
                    FilterChip(selected = outputType == t, onClick = { outputType = t }, label = { Text(t) })
                    Spacer(Modifier.width(8.dp))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(label = { Text("Batch Mode") }, onClick = { batchMode = !batchMode })
                Spacer(Modifier.width(12.dp))
                Text(if (batchMode) "ON" else "OFF")
                Spacer(Modifier.width(24.dp))
                AssistChip(label = { Text("Low RAM") }, onClick = { lowRam = !lowRam })
                Spacer(Modifier.width(12.dp))
                Text(if (lowRam) "ON" else "OFF")
            }

            OutlinedTextField(
                value = unitImages,
                onValueChange = { unitImages = it.filter { ch -> ch.isDigit() } },
                label = { Text("Unit Images (Low RAM)") }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Width Enforce: ")
                Spacer(Modifier.width(8.dp))
                listOf(0, 1, 2).forEach { w ->
                    FilterChip(selected = widthEnforce == w, onClick = { widthEnforce = w }, label = { Text("$w") })
                    Spacer(Modifier.width(8.dp))
                }
            }

            if (widthEnforce == 2) {
                OutlinedTextField(
                    value = customWidth,
                    onValueChange = { customWidth = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Custom Width") }
                )
            }

            OutlinedTextField(
                value = sensitivity,
                onValueChange = { sensitivity = it.filter { ch -> ch.isDigit() } },
                label = { Text("Sensitivity [0-100]") }
            )
            OutlinedTextField(
                value = ignorable,
                onValueChange = { ignorable = it.filter { ch -> ch.isDigit() } },
                label = { Text("Ignorable Border Pixels") }
            )
            OutlinedTextField(
                value = scanStep,
                onValueChange = { scanStep = it.filter { ch -> ch.isDigit() } },
                label = { Text("Scan Line Step [1-20]") }
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Kemas Output")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val options = listOf(
                        PackagingOption.FOLDER to "Folder",
                        PackagingOption.ZIP to ".zip",
                        PackagingOption.PDF to ".pdf"
                    )
                    options.forEach { (option, label) ->
                        FilterChip(
                            selected = packagingOption == option,
                            onClick = { packagingOption = option },
                            label = { Text(label) }
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                }
                Text(
                    when (packagingOption) {
                        PackagingOption.FOLDER -> "Output disalin sebagai folder biasa"
                        PackagingOption.ZIP -> "Output dikemas menjadi arsip ZIP"
                        PackagingOption.PDF -> "Output digabung sebagai satu file PDF"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // ----------------------------------------

            Button(
                enabled = !isRunning && (inputUri != null || batoUrl.isNotBlank()),
                onClick = {
                    isRunning = true
                    logText = ""
                    progressTarget = 0f
                    progressVisible = true
                    val progressFile = java.io.File(context.cacheDir, "progress.json")
                    var progressJob: Job? = null
                    var processedSteps = 0
                    var totalSteps = 1
                    val useBato = batoUrl.isNotBlank()
                    val trimmedBato = batoUrl.trim()
                    val inUri = inputUri
                    scope.launch {
                        try {
                            suspend fun writeProgressSafe(done: Boolean = false) {
                                withContext(Dispatchers.IO) {
                                    writeProgress(progressFile, processedSteps, totalSteps, done)
                                }
                            }

                            suspend fun readProgressCounts(): Pair<Int, Int>? {
                                return withContext(Dispatchers.IO) {
                                    if (progressFile.exists()) runCatching {
                                        val json = org.json.JSONObject(progressFile.readText())
                                        json.optInt("processed") to json.optInt("total")
                                    }.getOrNull() else null
                                }
                            }

                            suspend fun stepProgress(delta: Int = 1, done: Boolean = false) {
                                processedSteps += delta
                                totalSteps = maxOf(totalSteps, processedSteps)
                                writeProgressSafe(done)
                            }

                            if (!useBato && inUri == null) {
                                logText += "\nERROR: Folder input belum dipilih"
                                isRunning = false
                                progressVisible = false
                                return@launch
                            }
                            // 1) File I/O → IO dispatcher
                            val cacheIn: String
                            val outputFolderName: String
                            withContext(Dispatchers.IO) {
                                progressFile.parentFile?.mkdirs()
                                if (progressFile.exists()) progressFile.delete()
                            }
                            writeProgressSafe(done = false)
                            if (useBato) {
                                val inputDirParent = preferredBatoDownloadDir(context)
                                withContext(Dispatchers.IO) { inputDirParent.deleteRecursively(); inputDirParent.mkdirs() }
                                val downloadResult = withContext(Dispatchers.Default) {
                                    if (!Python.isStarted()) {
                                        Python.start(AndroidPlatform(context))
                                    }
                                    val py = Python.getInstance()
                                    val bato = py.getModule("bato")
                                    bato.callAttr(
                                        "download_bato",
                                        trimmedBato,
                                        inputDirParent.absolutePath,
                                        progressFile.absolutePath,
                                        processedSteps,
                                        0
                                    ).toString()
                                }
                                val downloadJson = org.json.JSONObject(downloadResult)
                                val downloadedCount = downloadJson.optInt("count", 0)
                                processedSteps += downloadedCount
                                totalSteps = maxOf(totalSteps, processedSteps)
                                writeProgressSafe(done = false)
                                cacheIn = downloadJson.getString("path")
                                val webpCount = withContext(Dispatchers.IO) { countWebpFiles(java.io.File(cacheIn)) }
                                if (webpCount > 0) {
                                    totalSteps = processedSteps + webpCount
                                    writeProgressSafe(done = false)
                                }
                                val converted = withContext(Dispatchers.IO) {
                                    convertWebpToPng(
                                        java.io.File(cacheIn),
                                        progressFile,
                                        processedSteps,
                                        totalSteps
                                    )
                                }
                                processedSteps += converted
                                totalSteps = maxOf(totalSteps, processedSteps)
                                writeProgressSafe(done = false)
                                outputFolderName = (java.io.File(cacheIn).name.trimEnd() + " [Stitched]").ifBlank { "output [Stitched]" }
                            } else {
                                val resolvedInputUri = requireNotNull(inUri) { "Folder input belum dipilih" }
                                cacheIn = withContext(Dispatchers.IO) {
                                    val dir = java.io.File(context.cacheDir, "input")
                                    dir.deleteRecursively(); dir.mkdirs()
                                    copyFromTree(context, resolvedInputUri, dir)
                                    dir.absolutePath
                                }
                                val inputDoc = DocumentFile.fromTreeUri(context, resolvedInputUri)
                                outputFolderName = ((inputDoc?.name ?: "output").trimEnd() + " [Stitched]").ifBlank { "output [Stitched]" }
                            }
                            val cacheOutParent = java.io.File(context.cacheDir, "output")
                            val cacheOut = withContext(Dispatchers.IO) {
                                cacheOutParent.deleteRecursively()
                                cacheOutParent.mkdirs()
                                val dir = java.io.File(cacheOutParent, outputFolderName)
                                dir.mkdirs()
                                dir.absolutePath
                            }

                            // 2) Python init + run → worker thread (Default) agar UI tidak beku
                            try {
                                progressJob = launch {
                                    while (isActive) {
                                        val snapshot = withContext(Dispatchers.IO) {
                                            if (progressFile.exists()) runCatching {
                                                progressFile.readText()
                                            }.getOrNull() else null
                                        }
                                        if (snapshot != null) {
                                            runCatching { org.json.JSONObject(snapshot) }.getOrNull()?.let { json ->
                                                when {
                                                    json.optBoolean("done", false) -> {
                                                        progressTarget = 1f
                                                        return@let
                                                    }
                                                    json.has("processed") && json.has("total") -> {
                                                        val processed = json.optInt("processed")
                                                        val total = json.optInt("total")
                                                        if (total > 0) {
                                                            val ratio = (processed.toFloat() / total).coerceIn(0f, 1f)
                                                            progressTarget = ratio
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        delay(150)
                                    }
                                }

                                withContext(Dispatchers.Default) {
                                    if (!Python.isStarted()) {
                                        Python.start(AndroidPlatform(context))
                                    }
                                    val py = Python.getInstance()
                                    val bridge = py.getModule("bridge")
                                    bridge.callAttr(
                                        "run",
                                        cacheIn,
                                        (splitHeight.toIntOrNull() ?: 5000),
                                        outputType,
                                        batchMode,
                                        widthEnforce,
                                        (customWidth.toIntOrNull() ?: 720),
                                        (sensitivity.toIntOrNull() ?: 90),
                                        (ignorable.toIntOrNull() ?: 0),
                                        (scanStep.toIntOrNull() ?: 5),
                                        lowRam,
                                        (unitImages.toIntOrNull() ?: 20),
                                        cacheOut,
                                        packagingOption == PackagingOption.ZIP,
                                        packagingOption == PackagingOption.PDF,
                                        progressFile.absolutePath,
                                        processedSteps,
                                        false
                                    )
                                }
                            } finally {
                            }

                            readProgressCounts()?.let { (p, t) ->
                                processedSteps = p
                                totalSteps = maxOf(t, processedSteps)
                            }
                            val packagingSteps = when (packagingOption) {
                                PackagingOption.FOLDER -> 2
                                PackagingOption.ZIP, PackagingOption.PDF -> 3
                            }
                            totalSteps = maxOf(totalSteps, processedSteps + packagingSteps)
                            writeProgressSafe(done = false)
                            stepProgress()

                        // 3) Salin hasil balik → IO dispatcher
                        withContext(Dispatchers.IO) {
                            val destinationTree = when {
                                    outputUri != null -> outputUri?.let { DocumentFile.fromTreeUri(context, it) }
                                    useBato -> null
                                    else -> DocumentFile.fromTreeUri(context, requireNotNull(inUri))
                                }
                                if (useBato && destinationTree == null) {
                                    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                                    val targetRoot = java.io.File(documentsDir, "AstralStitch").apply { mkdirs() }
                                    when (packagingOption) {
                                        PackagingOption.ZIP -> {
                                            val zipFile = java.io.File("$cacheOut.zip")
                                            require(zipFile.exists()) { "File ZIP tidak ditemukan" }
                                            zipFile.copyTo(java.io.File(targetRoot, zipFile.name), overwrite = true)
                                        }
                                        PackagingOption.PDF -> {
                                            val pdfFile = java.io.File("$cacheOut.pdf")
                                            require(pdfFile.exists()) { "File PDF tidak ditemukan" }
                                            pdfFile.copyTo(java.io.File(targetRoot, pdfFile.name), overwrite = true)
                                        }
                                        PackagingOption.FOLDER -> {
                                            val src = java.io.File(cacheOut)
                                            require(src.exists()) { "Folder output tidak ditemukan" }
                                            val destDir = java.io.File(targetRoot, src.name)
                                            if (destDir.exists()) destDir.deleteRecursively()
                                            src.copyRecursively(destDir, overwrite = true)
                                        }
                                    }
                                } else {
                                    val targetTree = requireNotNull(destinationTree) { "Tidak bisa mengakses folder tujuan" }
                                    when (packagingOption) {
                                        PackagingOption.ZIP -> {
                                            val zipFile = java.io.File("$cacheOut.zip")
                                            require(zipFile.exists()) { "File ZIP tidak ditemukan" }
                                            copyToTree(context, zipFile, targetTree, "application/zip")
                                        }
                                        PackagingOption.PDF -> {
                                            val pdfFile = java.io.File("$cacheOut.pdf")
                                            require(pdfFile.exists()) { "File PDF tidak ditemukan" }
                                            copyToTree(context, pdfFile, targetTree, "application/pdf")
                                        }
                                        PackagingOption.FOLDER -> {
                                            val src = java.io.File(cacheOut)
                                            require(src.exists()) { "Folder output tidak ditemukan" }
                                            copyToTree(context, src, targetTree)
                                        }
                                    }
                                }
                                cacheOutParent.deleteRecursively()
                            }

                            stepProgress(packagingSteps - 1, done = true)
                            progressTarget = 1f

                            progressJob?.cancelAndJoin()

                            logText += "\nSelesai."
                        } catch (e: Exception) {
                            logText += "\nERROR: ${e.message}"
                        } finally {
                            progressJob?.cancelAndJoin()
                            isRunning = false
                            progressVisible = false
                        }
                    }
                }
            ) { Text(if (isRunning) "Memproses..." else "Mulai") }

            if (progressVisible) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = animatedProgress.value, modifier = Modifier.fillMaxWidth())
                    Text("${(animatedProgress.value * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = logText,
                onValueChange = {},
                label = { Text("Log") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }
    }
}

fun preferredBatoDownloadDir(context: android.content.Context): java.io.File {
    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val target = java.io.File(documentsDir, "AstralStitch/BatoCache")
    return try {
        target.mkdirs()
        if (target.exists() && target.canWrite()) target else java.io.File(context.cacheDir, "bato_input")
    } catch (_: Exception) {
        java.io.File(context.cacheDir, "bato_input")
    }
}

fun writeProgress(progressFile: java.io.File, processed: Int, total: Int, done: Boolean = false) {
    runCatching {
        val payload = org.json.JSONObject().apply {
            put("processed", processed)
            put("total", total)
            if (done) put("done", true)
        }
        progressFile.writeText(payload.toString())
    }
}

fun countWebpFiles(root: java.io.File): Int {
    if (!root.exists()) return 0
    return root.walkTopDown()
        .count { it.isFile && it.extension.equals("webp", ignoreCase = true) }
}

fun convertWebpToPng(
    root: java.io.File,
    progressFile: java.io.File? = null,
    processedOffset: Int = 0,
    totalSteps: Int? = null
): Int {
    if (!root.exists()) return 0
    var convertedCount = 0
    val total = totalSteps ?: countWebpFiles(root)
    root.walkTopDown()
        .filter { it.isFile && it.extension.equals("webp", ignoreCase = true) }
        .forEach { webpFile ->
            val baseName = webpFile.nameWithoutExtension
            var target = java.io.File(webpFile.parentFile, "$baseName.png")
            var index = 1
            while (target.exists()) {
                target = java.io.File(webpFile.parentFile, "${baseName}_webp$index.png")
                index += 1
            }

            val bitmap = BitmapFactory.decodeFile(webpFile.absolutePath)
            if (bitmap != null) {
                target.outputStream().use { outs ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outs)
                }
                bitmap.recycle()
                webpFile.delete()
                convertedCount += 1
                progressFile?.let {
                    writeProgress(it, processedOffset + convertedCount, processedOffset + total)
                }
            }
        }
    return convertedCount
}

// ===== SAF helpers =====

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

    // anak-anak root langsung ke 'dest' tanpa subfolder tambahan
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
