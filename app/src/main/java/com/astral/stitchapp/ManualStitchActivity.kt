package com.astral.stitchapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.astral.stitchapp.ui.theme.AstralStitchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

data class ImageMeta(
    val path: String,
    val width: Int,
    val height: Int,
    val startY: Int,
    val endY: Int
)

class ManualStitchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imagePaths = intent.getStringArrayListExtra("imagePaths") ?: arrayListOf()
        val initialCutPositions = intent.getIntArrayExtra("initialCutPositions") ?: intArrayOf()
        val outputFolderPath = intent.getStringExtra("outputFolder") ?: ""
        val outputUriStr = intent.getStringExtra("outputUri")
        val outputType = intent.getStringExtra("outputType") ?: ".png"
        val quality = intent.getIntExtra("quality", 100)
        val packaging = intent.getStringExtra("packaging") ?: "FOLDER"
        val pdfPassword = intent.getStringExtra("pdfPassword")
        val zipPassword = intent.getStringExtra("zipPassword")

        setContent {
            val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val isDarkTheme = remember { prefs.getBoolean("dark_mode", false) }

            AstralStitchTheme(darkTheme = isDarkTheme) {
                ManualStitchScreen(
                    imagePaths = imagePaths,
                    initialCutPositions = initialCutPositions,
                    outputFolderPath = outputFolderPath,
                    outputUriStr = outputUriStr,
                    outputType = outputType,
                    quality = quality,
                    packaging = packaging,
                    pdfPassword = pdfPassword,
                    zipPassword = zipPassword,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun DisplayImageItem(
    imagePath: String,
    targetWidthPx: Int,
    displayHeightDp: Dp,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(imagePath, targetWidthPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imagePath, targetWidthPx) {
        withContext(Dispatchers.IO) {
            val bmp = loadDisplayBitmap(imagePath, targetWidthPx)
            withContext(Dispatchers.Main) {
                bitmap = bmp
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(displayHeightDp)
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } ?: CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
    }
}

private fun loadDisplayBitmap(filePath: String, targetWidthPx: Int): Bitmap? {
    return try {
        val file = File(filePath)
        if (!file.exists()) return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val srcW = options.outWidth
        if (srcW <= 0) return null

        var sampleSize = 1
        val reqW = maxOf(1, targetWidthPx)
        while (srcW / (sampleSize * 2) >= reqW) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        if (bmp != null) return bmp

        if (file.extension.equals("jxl", ignoreCase = true)) {
            return com.awxkee.jxlcoder.JxlCoder.decode(file.readBytes())
        }
        null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualStitchScreen(
    imagePaths: List<String>,
    initialCutPositions: IntArray,
    outputFolderPath: String,
    outputUriStr: String?,
    outputType: String,
    quality: Int,
    packaging: String = "FOLDER",
    pdfPassword: String? = null,
    zipPassword: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val imageMetas = remember(imagePaths) {
        var currY = 0
        imagePaths.map { path ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            val w = maxOf(1, options.outWidth)
            val h = maxOf(1, options.outHeight)
            val meta = ImageMeta(path, w, h, currY, currY + h)
            currY += h
            meta
        }
    }

    val totalCanvasHeight = remember(imageMetas) { imageMetas.lastOrNull()?.endY ?: 0 }
    val canvasWidth = remember(imageMetas) { imageMetas.maxOfOrNull { it.width } ?: 720 }

    val cutPositions = remember {
        mutableStateListOf<Int>().apply {
            addAll(initialCutPositions.sorted().filter { it in 1 until totalCanvasHeight })
        }
    }

    var isSaving by remember { mutableStateOf(false) }
    var saveProgressText by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val density = LocalDensity.current.density
        val scale = remember(screenWidthPx, canvasWidth) {
            if (canvasWidth > 0) screenWidthPx / canvasWidth.toFloat() else 1f
        }

        val scrollPx = remember(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset, imageMetas, scale) {
            val idx = lazyListState.firstVisibleItemIndex
            val offset = lazyListState.firstVisibleItemScrollOffset
            val startY = if (idx < imageMetas.size) imageMetas[idx].startY else 0
            (startY * scale) + offset
        }

        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    TopAppBar(
                        title = { Text("Manual Stitch Editor", style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            Button(
                                onClick = {
                                    if (isSaving) return@Button
                                    isSaving = true
                                    scope.launch(Dispatchers.IO) {
                                        saveManualSlices(
                                            context = context,
                                            imageMetas = imageMetas,
                                            cutPositions = cutPositions.toList(),
                                            canvasWidth = canvasWidth,
                                            totalCanvasHeight = totalCanvasHeight,
                                            outputFolderPath = outputFolderPath,
                                            outputUriStr = outputUriStr,
                                            outputType = outputType,
                                            quality = quality,
                                            packaging = packaging,
                                            pdfPassword = pdfPassword,
                                            zipPassword = zipPassword,
                                            onProgress = { txt ->
                                                scope.launch(Dispatchers.Main) { saveProgressText = txt }
                                            },
                                            onSuccess = {
                                                scope.launch(Dispatchers.Main) {
                                                    isSaving = false
                                                    Toast.makeText(context, "Hasil potong berhasil disimpan!", Toast.LENGTH_LONG).show()
                                                    onBack()
                                                }
                                            },
                                            onError = { err ->
                                                scope.launch(Dispatchers.Main) {
                                                    isSaving = false
                                                    Toast.makeText(context, "Gagal menyimpan: $err", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        )
                                    }
                                },
                                enabled = !isSaving
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Simpan")
                            }
                        }
                    )

                    if (cutPositions.isNotEmpty()) {
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                itemsIndexed(cutPositions) { index, cutY ->
                                    val cutNum = index + 1
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            val screenHeightPx = this@BoxWithConstraints.constraints.maxHeight.toFloat()
                                            val targetScrollPx = maxOf(0f, (cutY * scale) - (screenHeightPx / 2f))
                                            var targetIdx = 0
                                            var targetOffsetPx = 0
                                            for (i in imageMetas.indices) {
                                                val itemStartPx = imageMetas[i].startY * scale
                                                val itemEndPx = imageMetas[i].endY * scale
                                                if (targetScrollPx in itemStartPx..itemEndPx) {
                                                    targetIdx = i
                                                    targetOffsetPx = (targetScrollPx - itemStartPx).roundToInt()
                                                    break
                                                }
                                            }
                                            scope.launch {
                                                lazyListState.scrollToItem(targetIdx, targetOffsetPx)
                                            }
                                        },
                                        label = { Text("Potong #$cutNum (${cutY}px)", style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(imageMetas) { _, meta ->
                        val displayHeightDp = ((meta.height * scale) / density).dp
                        val targetW = screenWidthPx.roundToInt()

                        DisplayImageItem(
                            imagePath = meta.path,
                            targetWidthPx = targetW,
                            displayHeightDp = displayHeightDp
                        )
                    }
                }

                cutPositions.forEachIndexed { index, cutY ->
                    val screenYPx = (cutY * scale) - scrollPx
                    val screenYDp = (screenYPx / density).dp

                    if (screenYPx >= -100f && screenYPx <= this@BoxWithConstraints.constraints.maxHeight + 100f) {
                        val prevY = if (index > 0) cutPositions[index - 1] else 0
                        val nextY = if (index < cutPositions.size - 1) cutPositions[index + 1] else totalCanvasHeight
                        val topHeight = cutY - prevY
                        val bottomHeight = nextY - cutY

                        DraggableCutBar(
                            cutIndex = index + 1,
                            topHeight = topHeight,
                            bottomHeight = bottomHeight,
                            offsetY = screenYDp,
                            onDrag = { dragAmountPx ->
                                val deltaCanvasY = (dragAmountPx / scale).roundToInt()
                                val minAllowedY = if (index > 0) cutPositions[index - 1] + 50 else 50
                                val maxAllowedY = if (index < cutPositions.size - 1) cutPositions[index + 1] - 50 else totalCanvasHeight - 50

                                val newY = (cutPositions[index] + deltaCanvasY).coerceIn(minAllowedY, maxAllowedY)
                                cutPositions[index] = newY
                            }
                        )
                    }
                }

                if (isSaving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = if (saveProgressText.isNotBlank()) saveProgressText else "Menyimpan potongan gambar...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DraggableCutBar(
    cutIndex: Int,
    topHeight: Int,
    bottomHeight: Int,
    offsetY: Dp,
    onDrag: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .offset(y = offsetY - 20.dp)
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(cutIndex) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.Red)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Red,
            contentColor = Color.White,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "✂️ #$cutIndex | Atas: ${topHeight}px | Bawah: ${bottomHeight}px",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
            }
        }
    }
}

private fun saveManualSlices(
    context: Context,
    imageMetas: List<ImageMeta>,
    cutPositions: List<Int>,
    canvasWidth: Int,
    totalCanvasHeight: Int,
    outputFolderPath: String,
    outputUriStr: String?,
    outputType: String,
    quality: Int,
    packaging: String = "FOLDER",
    pdfPassword: String? = null,
    zipPassword: String? = null,
    onProgress: (String) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        val allCuts = mutableListOf<Int>().apply {
            add(0)
            addAll(cutPositions)
            add(totalCanvasHeight)
        }

        val outputDir = File(outputFolderPath)
        if (!outputDir.exists()) outputDir.mkdirs()

        onProgress("Memuat gambar...")

        val loadedBitmaps = mutableListOf<Bitmap>()
        for ((idx, meta) in imageMetas.withIndex()) {
            onProgress("Memproses gambar ${idx + 1}/${imageMetas.size}...")
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bmp = BitmapFactory.decodeFile(meta.path, options)
            if (bmp == null && File(meta.path).extension.equals("jxl", ignoreCase = true)) {
                bmp = com.awxkee.jxlcoder.JxlCoder.decode(File(meta.path).readBytes())
            }
            if (bmp != null) {
                if (bmp.width != canvasWidth) {
                    val ratio = bmp.height.toDouble() / bmp.width.toDouble()
                    val targetH = (ratio * canvasWidth).toInt().coerceAtLeast(1)
                    val resized = Bitmap.createScaledBitmap(bmp, canvasWidth, targetH, true)
                    if (resized != bmp) bmp.recycle()
                    bmp = resized
                }
                loadedBitmaps.add(bmp)
            }
        }

        if (loadedBitmaps.isEmpty()) {
            onError("Tidak ada gambar yang berhasil dimuat")
            return
        }

        val canvas = SmartStitcher.VirtualCanvas(loadedBitmaps)

        val totalSlices = allCuts.size - 1
        val ext = outputType.removePrefix(".")

        for (j in 0 until totalSlices) {
            val sliceStartY = allCuts[j]
            val sliceEndY = allCuts[j + 1]
            val sliceH = sliceEndY - sliceStartY
            if (sliceH <= 0) continue

            onProgress("Memotong slice ${j + 1}/$totalSlices...")

            val sliceBmp = canvas.extractSlice(0, sliceStartY, canvasWidth, sliceH)

            val fileName = String.format(Locale.ROOT, "%02d.%s", j + 1, ext)
            val outFile = File(outputDir, fileName)

            when (outputType.lowercase(Locale.ROOT)) {
                ".bmp" -> MainActivity.saveAsBmp(sliceBmp, outFile)
                ".jpg", ".jpeg" -> {
                    outFile.outputStream().buffered().use { out ->
                        sliceBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                    }
                }
                ".webp" -> {
                    outFile.outputStream().buffered().use { out ->
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            if (quality >= 100) {
                                sliceBmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                            } else {
                                sliceBmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.coerceIn(1, 100), out)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            sliceBmp.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), out)
                        }
                    }
                }
                else -> { // .png
                    outFile.outputStream().buffered().use { out ->
                        sliceBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            }

            sliceBmp.recycle()
        }

        canvas.recycle()

        onProgress("Memproses format kemasan ($packaging)...")
        val rawFile = outputDir
        val packagingOpt = try { PackagingOption.valueOf(packaging) } catch (e: Exception) { PackagingOption.FOLDER }
        val finalFile = MainActivity.processOutput(
            rawFile,
            outputType,
            packagingOpt,
            quality,
            pdfPassword.takeIf { !it.isNullOrBlank() },
            zipPassword.takeIf { !it.isNullOrBlank() }
        )

        if (!outputUriStr.isNullOrBlank()) {
            onProgress("Menyimpan ke folder tujuan SAF...")
            val targetTree = DocumentFile.fromTreeUri(context, Uri.parse(outputUriStr))
            if (targetTree != null) {
                if (finalFile.isDirectory) {
                    copyToTree(context, finalFile, targetTree)
                } else {
                    val mime = if (finalFile.extension == "pdf") "application/pdf" else "application/zip"
                    copyToTree(context, finalFile, targetTree, mime)
                }
            }
            if (finalFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                finalFile.deleteRecursively()
            }
        }

        onSuccess()
    } catch (e: Exception) {
        e.printStackTrace()
        onError(e.message ?: "Unknown error")
    }
}
