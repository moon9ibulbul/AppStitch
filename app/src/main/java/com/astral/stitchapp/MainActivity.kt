package com.astral.stitchapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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
    var bubbleProtect by remember { mutableStateOf(true) }
    var detectorPadding by remember { mutableStateOf("12") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }

    var logText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressVisible by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

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

    Scaffold(topBar = { TopAppBar(title = { Text("Smart Stitch (Android)") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Pilih folder sumber (INPUT) & tujuan (OUTPUT). App menyalin dari SAF ke cache agar Python bisa memproses.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickInput.launch(null) }) {
                    Text(if (inputUri == null) "Pilih Folder INPUT" else "Ganti Folder INPUT")
                }
                Spacer(Modifier.width(8.dp))
                Text(inputUri?.lastPathSegment ?: "Belum dipilih")
            }
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
                listOf(".png", ".jpg", ".webp", ".bmp", ".tiff", ".tga").forEach { t ->
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = bubbleProtect,
                    onClick = { bubbleProtect = !bubbleProtect },
                    label = { Text("Bubble Protect AI") }
                )
                Spacer(Modifier.width(12.dp))
                Text(if (bubbleProtect) "Deteksi aktif" else "Deteksi nonaktif")
            }
            if (bubbleProtect) {
                OutlinedTextField(
                    value = detectorPadding,
                    onValueChange = { detectorPadding = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Padding Detektor (px)") }
                )
            }
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
                enabled = !isRunning && inputUri != null,
                onClick = {
                    isRunning = true
                    logText = ""
                    progress = 0f
                    progressVisible = true
                    val inUri = inputUri!!
                    scope.launch {
                        try {
                            // 1) File I/O → IO dispatcher
                            val cacheIn = withContext(Dispatchers.IO) {
                                val dir = java.io.File(context.cacheDir, "input")
                                dir.deleteRecursively(); dir.mkdirs()
                                copyFromTree(context, inUri, dir)
                                dir.absolutePath
                            }
                            val inputDoc = DocumentFile.fromTreeUri(context, inUri)
                            val outputFolderName = ((inputDoc?.name ?: "output").trimEnd() + " [Stitched]").ifBlank { "output [Stitched]" }
                            val cacheOutParent = java.io.File(context.cacheDir, "output")
                            val cacheOut = withContext(Dispatchers.IO) {
                                cacheOutParent.deleteRecursively()
                                cacheOutParent.mkdirs()
                                val dir = java.io.File(cacheOutParent, outputFolderName)
                                dir.mkdirs()
                                dir.absolutePath
                            }

                            // 2) Python init + run → worker thread (Default) agar UI tidak beku
                            val progressFile = java.io.File(cacheOut, "progress.json")
                            var progressJob: Job? = null
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
                                                        progress = 1f
                                                        return@let
                                                    }
                                                    json.has("processed") && json.has("total") -> {
                                                        val processed = json.optInt("processed")
                                                        val total = json.optInt("total")
                                                        if (total > 0) {
                                                            val ratio = (processed.toFloat() / total).coerceIn(0f, 1f)
                                                            progress = ratio
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (progress >= 1f) break
                                        delay(300)
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
                                        bubbleProtect,
                                        (detectorPadding.toIntOrNull() ?: 12)
                                    )
                                }
                                progress = 1f
                            } finally {
                                progressJob?.cancelAndJoin()
                                progress = progress.coerceIn(0f, 1f)
                            }

                            // 3) Salin hasil balik → IO dispatcher
                            withContext(Dispatchers.IO) {
                                val destinationTree = outputUri?.let { DocumentFile.fromTreeUri(context, it) }
                                    ?: DocumentFile.fromTreeUri(context, inUri)
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
                                cacheOutParent.deleteRecursively()
                            }

                            logText += "\nSelesai."
                        } catch (e: Exception) {
                            logText += "\nERROR: ${e.message}"
                        } finally {
                            isRunning = false
                            progressVisible = false
                        }
                    }
                }
            ) { Text(if (isRunning) "Memproses..." else "Mulai") }

            if (progressVisible) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
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

// ===== SAF helpers =====

fun copyFromTree(ctx: android.content.Context, treeUri: Uri, dest: java.io.File) {
    val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return

    fun copy(doc: DocumentFile, base: java.io.File, isRoot: Boolean) {
        if (doc.isDirectory) {
            val targetDir = if (isRoot) base else java.io.File(base, doc.name ?: "dir").also { it.mkdirs() }
            for (child in doc.listFiles()) copy(child, targetDir, false)
        } else {
            val originalName = doc.name ?: "file.bin"
            val lower = originalName.lowercase(Locale.ROOT)
            if (lower.endsWith(".webp")) {
                val baseName = originalName.dropLast(5)
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
                    val fallbackFile = java.io.File(base, originalName)
                    ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                        fallbackFile.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                }
            } else {
                val outFile = java.io.File(base, originalName)
                ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    outFile.outputStream().use { outs -> ins.copyTo(outs) }
                }
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
