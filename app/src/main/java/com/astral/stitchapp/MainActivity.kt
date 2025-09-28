package com.astral.stitchapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile

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

    var logText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // persist permission
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
            // ----------------------------------------

            Button(
                enabled = !isRunning && inputUri != null && outputUri != null,
                onClick = {
                    isRunning = true
                    logText = ""
                    val inUri = inputUri!!
                    val outUri = outputUri!!
                    scope.launch {
                        try {
                            val cacheIn = withContext(Dispatchers.IO) {
                                val dir = java.io.File(context.cacheDir, "input")
                                dir.deleteRecursively(); dir.mkdirs()
                                copyFromTree(context, inUri, dir) // <-- root children langsung ke dir
                                dir.absolutePath
                            }
                            val cacheOut = withContext(Dispatchers.IO) {
                                val dir = java.io.File(context.cacheDir, "output")
                                dir.deleteRecursively(); dir.mkdirs()
                                dir.absolutePath
                            }

                            // Pastikan Python start (defensif, selain PyApplication di manifest)
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
                                cacheOut
                            )

                            withContext(Dispatchers.IO) {
                                val outDoc = DocumentFile.fromTreeUri(context, outUri)
                                val src = java.io.File(cacheOut)
                                copyToTree(context, src, outDoc)
                            }
                            logText += "\nSelesai."
                        } catch (e: Exception) {
                            logText += "\nERROR: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                }
            ) { Text(if (isRunning) "Memproses..." else "Mulai") }

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
            val outFile = java.io.File(base, doc.name ?: "file.bin")
            ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                outFile.outputStream().use { outs -> ins.copyTo(outs) }
            }
        }
    }

    // penting: anak-anak root langsung ke 'dest' tanpa subfolder tambahan
    copy(root, dest, true)
}

fun copyToTree(ctx: android.content.Context, src: java.io.File, destTree: DocumentFile?) {
    if (destTree == null) return
    fun upload(file: java.io.File, parent: DocumentFile) {
        if (file.isDirectory) {
            val dir = parent.findFile(file.name) ?: parent.createDirectory(file.name)!!
            file.listFiles()?.forEach { upload(it, dir) }
        } else {
            val mime = "image/*"
            val target = parent.findFile(file.name) ?: parent.createFile(mime, file.name)!!
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { outs ->
                file.inputStream().use { ins -> ins.copyTo(outs) }
            }
        }
    }
    upload(src, destTree)
}
