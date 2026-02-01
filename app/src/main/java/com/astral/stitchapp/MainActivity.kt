package com.astral.stitchapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.astral.stitchapp.ui.theme.AstralStitchTheme
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

val PauseIcon: ImageVector
    get() {
        if (_pauseIcon != null) return _pauseIcon!!
        _pauseIcon = ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(5.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }.build()
        return _pauseIcon!!
    }
private var _pauseIcon: ImageVector? = null

enum class PackagingOption {
    FOLDER, ZIP, PDF
}

data class Template(val name: String, val settings: JSONObject)

object TemplateManager {
    fun load(context: Context): List<Template> {
        val prefs = context.getSharedPreferences("stitch_templates", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", setOf()) ?: setOf()
        return set.mapNotNull {
            try {
                val json = JSONObject(it)
                Template(json.getString("name"), json.getJSONObject("settings"))
            } catch (e: Exception) { null }
        }.sortedBy { it.name }
    }

    fun save(context: Context, name: String, settings: JSONObject) {
        val prefs = context.getSharedPreferences("stitch_templates", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", mutableSetOf())!!.toMutableSet()
        set.removeIf {
            try { JSONObject(it).getString("name") == name } catch(e:Exception){false}
        }
        val tObj = JSONObject().apply {
            put("name", name)
            put("settings", settings)
        }
        set.add(tObj.toString())
        prefs.edit().putStringSet("templates", set).apply()
    }

    fun delete(context: Context, name: String) {
        val prefs = context.getSharedPreferences("stitch_templates", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("templates", mutableSetOf())!!.toMutableSet()
        set.removeIf {
            try { JSONObject(it).getString("name") == name } catch(e:Exception){false}
        }
        prefs.edit().putStringSet("templates", set).apply()
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        @JvmStatic
        fun saveAsBmp(bitmap: Bitmap, file: File) {
            val width = bitmap.width
            val height = bitmap.height
            val headerSize = 54
            val imageSize = width * height * 4
            val fileSize = headerSize + imageSize

            val buffer = java.nio.ByteBuffer.allocate(headerSize)
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // BMP Header
            buffer.put(0x42.toByte()); buffer.put(0x4D.toByte()) // "BM"
            buffer.putInt(fileSize)
            buffer.putInt(0) // Reserved
            buffer.putInt(headerSize) // Offset

            // DIB Header
            buffer.putInt(40) // Header size
            buffer.putInt(width)
            buffer.putInt(-height) // Negative height for top-down
            buffer.putShort(1.toShort()) // Planes
            buffer.putShort(32.toShort()) // BitCount
            buffer.putInt(0) // Compression (BI_RGB)
            buffer.putInt(imageSize)
            buffer.putInt(0); buffer.putInt(0)
            buffer.putInt(0); buffer.putInt(0)

            val intPixels = IntArray(width * height)
            bitmap.getPixels(intPixels, 0, width, 0, 0, width, height)
            val pixelBuffer = java.nio.ByteBuffer.allocate(imageSize)
            pixelBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pixelBuffer.asIntBuffer().put(intPixels)

            file.outputStream().use {
                it.write(buffer.array())
                it.write(pixelBuffer.array())
            }
        }

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

        @JvmStatic
        fun processOutput(finalFile: File, outputType: String, packaging: PackagingOption, quality: Int = 100): File {
            var resultFile = finalFile
            if (outputType == ".webp" && resultFile.isDirectory) {
                resultFile.listFiles()?.forEach { f ->
                    if (f.isFile && f.extension.equals("bmp", ignoreCase = true)) {
                        val bitmap = BitmapFactory.decodeFile(f.absolutePath)
                        if (bitmap != null) {
                            val webpFile = File(f.parent, f.nameWithoutExtension + ".webp")
                            webpFile.outputStream().use { out ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    if (quality == 100) {
                                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                                    } else {
                                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                                    }
                                } else {
                                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                                }
                            }
                            bitmap.recycle()
                            f.delete()
                        }
                    }
                }
            }

            if (resultFile.isDirectory && packaging != PackagingOption.FOLDER) {
                val py = Python.getInstance()
                val bridge = py.getModule("bridge")
                val packedPath = bridge.callAttr("pack_archive", resultFile.absolutePath, packaging.name).toString()
                resultFile = File(packedPath)
            }

            return resultFile
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Background Progress"
            val descriptionText = "Notifications for download and stitch progress"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("progress_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Request Permission for Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContent {
            val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val isDarkTheme = remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }

            AstralStitchTheme(darkTheme = isDarkTheme.value) {
                MainScreen(
                    isDarkTheme = isDarkTheme.value,
                    onThemeChange = { isDark ->
                        isDarkTheme.value = isDark
                        prefs.edit().putBoolean("dark_mode", isDark).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isDarkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val titles = listOf("Local", "Rawloader")
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var availableTemplates by remember { mutableStateOf(TemplateManager.load(context)) }

    fun refreshTemplates() {
        availableTemplates = TemplateManager.load(context)
    }

    if (showSettings) {
        SettingsScreen(
            onDismiss = { showSettings = false },
            isDarkTheme = isDarkTheme,
            onThemeChange = onThemeChange,
            onTemplatesImported = { refreshTemplates() }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("AstralStitch v1.4.0") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
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
            Box(Modifier.fillMaxSize().zIndex(if (selectedTab == 0) 1f else 0f).alpha(if (selectedTab == 0) 1f else 0f)) {
                StitchTab(
                    availableTemplates = availableTemplates,
                    onRefreshTemplates = { refreshTemplates() }
                )
            }
            Box(Modifier.fillMaxSize().zIndex(if (selectedTab == 1) 1f else 0f).alpha(if (selectedTab == 1) 1f else 0f)) {
                BatoTab(
                    availableTemplates = availableTemplates,
                    onRefreshTemplates = { refreshTemplates() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onTemplatesImported: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)

    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var bgProgress by remember { mutableStateOf(prefs.getBoolean("bg_progress", false)) }
    var defaultOutputUri by remember { mutableStateOf(prefs.getString("default_output_uri", null)) }

    val pickDefaultOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            defaultOutputUri = uri.toString()
            prefs.edit().putString("default_output_uri", uri.toString()).apply()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val tmplPrefs = context.getSharedPreferences("stitch_templates", android.content.Context.MODE_PRIVATE)
            val set = tmplPrefs.getStringSet("templates", setOf()) ?: setOf()
            val exportList = JSONArray()
            set.forEach { exportList.put(JSONObject(it)) }
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(exportList.toString(2).toByteArray())
                }
                Toast.makeText(context, "Templates Exported!", Toast.LENGTH_SHORT).show()
            } catch(e:Exception) {
                Toast.makeText(context, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    val jsonStr = ins.bufferedReader().readText()
                    val jsonArr = JSONArray(jsonStr)
                    val tmplPrefs = context.getSharedPreferences("stitch_templates", android.content.Context.MODE_PRIVATE)
                    val currentSet = tmplPrefs.getStringSet("templates", mutableSetOf())!!.toMutableSet()
                    var added = 0
                    for(i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        if(obj.has("name") && obj.has("settings")) {
                            val name = obj.getString("name")
                            currentSet.removeIf { JSONObject(it).getString("name") == name }
                            currentSet.add(obj.toString())
                            added++
                        }
                    }
                    tmplPrefs.edit().putStringSet("templates", currentSet).apply()
                    Toast.makeText(context, "Imported $added templates!", Toast.LENGTH_SHORT).show()
                    onTemplatesImported()
                }
            } catch(e:Exception) {
                Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var cacheSize by remember { mutableStateOf("Calculating...") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sizeBytes = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val mb = sizeBytes / (1024.0 * 1024.0)
            withContext(Dispatchers.Main) {
                cacheSize = "%.2f MB".format(mb)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Dark Mode")
                    Switch(checked = isDarkTheme, onCheckedChange = onThemeChange)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Notification Sound")
                    Switch(checked = soundEnabled, onCheckedChange = {
                        soundEnabled = it
                        prefs.edit().putBoolean("sound_enabled", it).apply()
                    })
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Background Progress (Notif)")
                    Switch(checked = bgProgress, onCheckedChange = {
                        bgProgress = it
                        prefs.edit().putBoolean("bg_progress", it).apply()
                        if (it) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    Toast.makeText(context, "Please grant notification permission", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    })
                }

                HorizontalDivider()

                Text("Rawloader Default Output:")
                Button(onClick = { pickDefaultOutput.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (defaultOutputUri != null) "Change Folder" else "Set Default Folder")
                }
                if (defaultOutputUri != null) {
                    Text(Uri.parse(defaultOutputUri).lastPathSegment ?: "Unknown", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Default: App Storage (Accessible via File Manager)", style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Cache: $cacheSize")
                    Button(onClick = {
                        context.cacheDir.deleteRecursively()
                        cacheSize = "0.00 MB"
                        Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Clear")
                    }
                }

                HorizontalDivider()

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakteer.id/astralexpresscrew/tip"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Donate Me ❤️")
                }

                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                     Button(onClick = { exportLauncher.launch("stitch_templates.json") }) { Text("Export Tmpl") }
                     Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import Tmpl") }
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
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
    widthEnforce: Int, onWidthEn: (Int)->Unit,
    customWidth: String, onCustW: (String)->Unit,
    sensitivity: String, onSens: (String)->Unit,
    ignorable: String, onIgn: (String)->Unit,
    scanStep: String, onScan: (String)->Unit,
    packaging: PackagingOption, onPack: (PackagingOption)->Unit,
    splitMode: Int, onSplitMode: (Int)->Unit,
    lowRam: Boolean, onLowRam: (Boolean)->Unit,
    quality: Int, onQuality: (Int)->Unit,
    currentTemplate: Template?,
    availableTemplates: List<Template>,
    onLoadTemplate: (Template?) -> Unit,
    onSaveTemplate: (String) -> Unit,
    onDeleteTemplate: (Template) -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var newTemplateName by remember { mutableStateOf("") }
    var expandedTemplates by remember { mutableStateOf(false) }
    var expandedMode by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stitch Mode: ")
                Spacer(Modifier.width(8.dp))
                Box {
                    OutlinedButton(onClick = { expandedMode = true }) { Text(if (splitMode == 1) "Direct (Fixed)" else "Smart (Legacy)") }
                    DropdownMenu(expanded = expandedMode, onDismissRequest = { expandedMode = false }) {
                        DropdownMenuItem(text = { Text("Smart (Legacy)") }, onClick = { onSplitMode(0); expandedMode = false })
                        DropdownMenuItem(text = { Text("Direct (Fixed)") }, onClick = { onSplitMode(1); expandedMode = false })
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Low RAM")
            Switch(checked = lowRam, onCheckedChange = onLowRam)
        }

        OutlinedTextField(
            value = splitHeight,
            onValueChange = { onSplitH(it.filter { ch -> ch.isDigit() }) },
            label = { Text("Split Height") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Output")
            Spacer(Modifier.width(8.dp))
            listOf(".png", ".jpg", ".webp").forEach { t ->
                FilterChip(selected = outputType == t, onClick = { onOutT(t) }, label = { Text(t) })
                Spacer(Modifier.width(8.dp))
            }
        }

        if (outputType == ".jpg" || outputType == ".webp") {
            Column {
                Text("Quality: $quality%")
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { onQuality(it.toInt()) },
                    valueRange = 50f..100f,
                    steps = 49
                )
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

        HorizontalDivider()
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
                            showSaveDialog = false
                            newTemplateName = ""
                        }
                    }) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

data class LocalQueueItem(
    val id: String,
    val path: String,
    val name: String,
    val status: String,
    val progress: Double
)

data class QueueItem(
    val id: String,
    val url: String,
    val title: String,
    val status: String,
    val progress: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchTab(
    availableTemplates: List<Template>,
    onRefreshTemplates: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Single item state
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Ready") }

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
    var splitMode by remember { mutableIntStateOf(0) }
    var lowRam by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(100) }

    var currentTemplate by remember { mutableStateOf<Template?>(null) }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inputUri = uri
            val doc = DocumentFile.fromTreeUri(context, uri)
            statusText = "Selected: ${doc?.name ?: "Unknown"}"
        }
    }

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
            put("splitMode", splitMode)
            put("lowRam", lowRam)
            put("quality", quality)
        }
        TemplateManager.save(context, name, settings)
        onRefreshTemplates()
        currentTemplate = Template(name, settings)
    }

    fun deleteTemplate(t: Template) {
        TemplateManager.delete(context, t.name)
        onRefreshTemplates()
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
            splitMode = s.optInt("splitMode", 0)
            lowRam = s.optBoolean("lowRam", false)
            quality = s.optInt("quality", 100)
        } else {
            splitHeight = "5000"
            outputType = ".png"
            customFileName = ""
            widthEnforce = 0
            customWidth = "720"
            sensitivity = "90"
            ignorable = "0"
            scanStep = "5"
            packagingOption = PackagingOption.FOLDER
            splitMode = 0
            lowRam = false
            quality = 100
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickInput.launch(null) }, enabled = !isProcessing) { Text("Select Input Folder") }
             Spacer(Modifier.width(8.dp))
             Text(
                 text = statusText,
                 modifier = Modifier.weight(1f),
                 overflow = TextOverflow.Ellipsis,
                 maxLines = 2
             )
        }

        if (isProcessing) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
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
            splitMode, { splitMode = it },
            lowRam, { lowRam = it },
            quality, { quality = it },
            currentTemplate,
            availableTemplates,
            ::applyTemplate, ::saveTemplate, ::deleteTemplate
        )

        HorizontalDivider()

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (inputUri == null) {
                    Toast.makeText(context, "Please select an input folder", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isProcessing = true
                progress = 0f
                statusText = "Processing..."

                scope.launch(Dispatchers.IO) {
                    try {
                        val uri = inputUri!!
                        val doc = DocumentFile.fromTreeUri(context, uri)
                        val name = doc?.name ?: "Unknown"

                        val cacheIn = File(context.cacheDir, "stitch_single_in")
                        cacheIn.deleteRecursively(); cacheIn.mkdirs()
                        copyFromTree(context, uri, cacheIn)

                        val cacheOutParent = File(context.cacheDir, "stitch_single_out")
                        val outputName = "$name [Stitched]"
                        cacheOutParent.deleteRecursively(); cacheOutParent.mkdirs()
                        val dir = File(cacheOutParent, outputName)
                        dir.mkdirs()

                        val py = Python.getInstance()
                        val bridge = py.getModule("bridge")
                        val progressFile = File(context.cacheDir, "prog_single.json")

                        val monitor = launch {
                            while(isActive) {
                                if(progressFile.exists()) {
                                    try {
                                        val j = JSONObject(progressFile.readText())
                                        val p = j.optInt("processed", 0)
                                        val t = j.optInt("total", 1)
                                        val prog = if(t>0) p.toFloat()/t else 0f
                                        withContext(Dispatchers.Main) {
                                            progress = prog
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
                            lowRam, 20,
                            dir.absolutePath,
                            customFileName.takeIf { it.isNotBlank() },
                            packagingOption == PackagingOption.ZIP,
                            packagingOption == PackagingOption.PDF,
                            progressFile.absolutePath, 0, true,
                            splitMode,
                            quality
                        ).toString()

                        monitor.cancel()

                        val rawFile = File(finalPathStr)
                        val finalFile = MainActivity.processOutput(rawFile, outputType, packagingOption, quality)

                        val targetTree = DocumentFile.fromTreeUri(context, uri)

                        if (targetTree != null && targetTree.canWrite()) {
                             if (finalFile.isDirectory) {
                                copyToTree(context, finalFile, targetTree)
                            } else {
                                val mime = if(finalFile.extension == "pdf") "application/pdf" else "application/zip"
                                copyToTree(context, finalFile, targetTree, mime)
                            }
                        }

                        cacheIn.deleteRecursively()
                        cacheOutParent.deleteRecursively()
                        progressFile.delete()

                        withContext(Dispatchers.Main) {
                            isProcessing = false
                            progress = 1f
                            statusText = "Done! Output saved to source folder."
                            Toast.makeText(context, "Stitching Complete!", Toast.LENGTH_SHORT).show()
                            val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                            if (prefs.getBoolean("sound_enabled", true)) {
                                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            isProcessing = false
                            statusText = "Error: ${e.message}"
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = if(isProcessing) Color.Gray else Color(0xFF4CAF50)),
            enabled = !isProcessing
        ) { Text(if (isProcessing) "PROCESSING..." else "START STITCHING") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatoTab(
    availableTemplates: List<Template>,
    onRefreshTemplates: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)

    var selectedSource by remember { mutableStateOf("Xbat") }
    var expandedSource by remember { mutableStateOf(false) }

    var urlInput by remember { mutableStateOf("") }
    var chapterInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf(prefs.getString("ridi_cookie", "") ?: "") }
    var kakaoCookieInput by remember { mutableStateOf(prefs.getString("kakao_cookie", "") ?: "") }
    var autoRetry by remember { mutableStateOf(true) }

    var queueItems by remember { mutableStateOf(listOf<QueueItem>()) }
    var isProcessorRunning by remember { mutableStateOf(false) }

    var concurrencyCount by remember { mutableIntStateOf(1) }
    var expandedConcurrency by remember { mutableStateOf(false) }
    var isAddingToQueue by remember { mutableStateOf(false) }

    // Settings
    var splitHeight by remember { mutableStateOf("5000") }
    var outputType by remember { mutableStateOf(".png") }
    var customFileName by remember { mutableStateOf("") }
    var widthEnforce by remember { mutableIntStateOf(0) }
    var customWidth by remember { mutableStateOf("720") }
    var sensitivity by remember { mutableStateOf("90") }
    var ignorable by remember { mutableStateOf("0") }
    var scanStep by remember { mutableStateOf("5") }
    var packagingOption by remember { mutableStateOf(PackagingOption.FOLDER) }
    var splitMode by remember { mutableIntStateOf(0) }
    var lowRam by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(100) }

    var currentTemplate by remember { mutableStateOf<Template?>(null) }

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
            put("splitMode", splitMode)
            put("lowRam", lowRam)
            put("quality", quality)
        }
        TemplateManager.save(context, name, settings)
        onRefreshTemplates()
        currentTemplate = Template(name, settings)
    }

    fun deleteTemplate(t: Template) {
        TemplateManager.delete(context, t.name)
        onRefreshTemplates()
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
            splitMode = s.optInt("splitMode", 0)
            lowRam = s.optBoolean("lowRam", false)
            quality = s.optInt("quality", 100)
        } else {
            splitHeight = "5000"
            outputType = ".png"
            customFileName = ""
            widthEnforce = 0
            customWidth = "720"
            sensitivity = "90"
            ignorable = "0"
            scanStep = "5"
            packagingOption = PackagingOption.FOLDER
            splitMode = 0
            lowRam = false
            quality = 100
        }
    }

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
                    withContext(Dispatchers.Main) {
                        queueItems = list
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(1000)
            }
        }
    }

    LaunchedEffect(isProcessorRunning, concurrencyCount) {
        if (!isProcessorRunning) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val jobs = List(concurrencyCount) {
                launch {
                    while (isActive && isProcessorRunning) {
                        val defaultUriStr = prefs.getString("default_output_uri", null)
                        val outputUri = if (defaultUriStr != null) Uri.parse(defaultUriStr) else null

                        val pendingCount = queueItems.count { it.status == "pending" || it.status == "downloading" || it.status == "stitching" || it.status == "initializing" }
                        if (pendingCount == 0 && queueItems.isNotEmpty()) {
                             withContext(Dispatchers.Main) {
                                isProcessorRunning = false
                                Toast.makeText(context, "All tasks completed!", Toast.LENGTH_SHORT).show()
                                if (prefs.getBoolean("sound_enabled", true)) {
                                    val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP)
                                }
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
                                put("splitMode", splitMode)
                                put("lowRam", lowRam)
                                put("quality", quality)
                            }
                            val resultStr = bato.callAttr("process_next_item", context.cacheDir.absolutePath, params.toString()).toString()
                            val result = JSONObject(resultStr)
                            if (result.has("status")) {
                                val status = result.getString("status")
                                if (status == "empty") {
                                    delay(2000)
                                } else if (status == "success") {
                                    val path = result.getString("path")
                                    val rawFile = File(path)
                                    val file = MainActivity.processOutput(rawFile, outputType, packagingOption, quality)

                                    if (outputUri != null) {
                                        val targetTree = DocumentFile.fromTreeUri(context, outputUri)
                                        if (targetTree != null && file.exists()) {
                                            if (file.isDirectory) {
                                                copyToTree(context, file, targetTree)
                                            } else {
                                                val mime = if(file.extension == "pdf") "application/pdf" else "application/zip"
                                                copyToTree(context, file, targetTree, mime)
                                            }
                                        }
                                    } else {
                                        // Default: App Storage (External Files Dir)
                                        // Usually Android/data/package/files/
                                        val destDir = context.getExternalFilesDir(null)
                                        if (destDir != null && file.exists()) {
                                            val destFile = File(destDir, file.name)
                                            file.copyTo(destFile, overwrite = true)
                                            if (file.isDirectory) {
                                                file.deleteRecursively() // Cleanup after move
                                            } else {
                                                file.delete()
                                            }
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
                            IconButton(onClick = { onPause(item.id) }, modifier = Modifier.size(32.dp)) { Icon(PauseIcon, contentDescription="Pause") }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (item.status == "paused") {
                            Button(onClick = { onRetry(item.id) }, modifier = Modifier.height(32.dp)) { Text("Resume", style = MaterialTheme.typography.labelSmall) }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (item.status == "failed") {
                            Button(onClick = { onRetry(item.id) }, modifier = Modifier.height(32.dp)) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) { Text("X") }
                    }
                }
                if (item.status == "downloading" || item.status == "stitching") {
                    LinearProgressIndicator(progress = item.progress.toFloat(), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Source: ")
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { expandedSource = true }) { Text(selectedSource) }
                DropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                    DropdownMenuItem(text = { Text("Xbat") }, onClick = { selectedSource = "Xbat"; expandedSource = false })
                    DropdownMenuItem(text = { Text("Ridibooks") }, onClick = { selectedSource = "Ridibooks"; expandedSource = false })
                    DropdownMenuItem(text = { Text("Naver Webtoon") }, onClick = { selectedSource = "Naver Webtoon"; expandedSource = false })
                    DropdownMenuItem(text = { Text("XToon") }, onClick = { selectedSource = "XToon"; expandedSource = false })
                    DropdownMenuItem(text = { Text("KakaoPage") }, onClick = { selectedSource = "KakaoPage"; expandedSource = false })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val labelText = when(selectedSource) {
                "Ridibooks" -> "Url (Book)"
                "Naver Webtoon" -> "Comic ID"
                "XToon" -> "Url (Chapter)"
                "KakaoPage" -> "Url (Chapter)"
                else -> "Url (Chapter/Series)"
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(labelText) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (isAddingToQueue) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Button(
                    onClick = {
                        if (selectedSource == "Naver Webtoon" && chapterInput.isBlank()) {
                            Toast.makeText(context, "Chapter ID required for Naver!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isAddingToQueue = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val py = Python.getInstance()
                                val bato = py.getModule("bato")
                                val type = when(selectedSource) {
                                    "Ridibooks" -> "ridi"
                                    "Naver Webtoon" -> "naver"
                                    "XToon" -> "xtoon"
                                    "KakaoPage" -> "kakao"
                                    else -> "bato"
                                }

                                val cookieToUse = if (type == "ridi") cookieInput else if (type == "kakao") kakaoCookieInput else ""

                                if (type == "naver" && chapterInput.contains("-")) {
                                    // Range support: 1-10
                                    val parts = chapterInput.split("-")
                                    if (parts.size == 2) {
                                        val start = parts[0].trim().toIntOrNull()
                                        val end = parts[1].trim().toIntOrNull()
                                        if (start != null && end != null && start <= end) {
                                            for (no in start..end) {
                                                val finalUrl = "https://comic.naver.com/webtoon/detail?titleId=$urlInput&no=$no"
                                                bato.callAttr("add_to_queue", context.cacheDir.absolutePath, finalUrl, type, cookieToUse)
                                            }
                                        }
                                    }
                                } else {
                                    val finalUrl = if (type == "naver") {
                                        "https://comic.naver.com/webtoon/detail?titleId=$urlInput&no=$chapterInput"
                                    } else {
                                        urlInput
                                    }
                                    bato.callAttr("add_to_queue", context.cacheDir.absolutePath, finalUrl, type, cookieToUse)
                                }

                                withContext(Dispatchers.Main) {
                                    urlInput = ""
                                    chapterInput = ""
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

        if (selectedSource == "Naver Webtoon") {
             OutlinedTextField(
                value = chapterInput,
                onValueChange = { chapterInput = it }, // Allow dash for range
                label = { Text("Chapter (No. or 1-10)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (selectedSource == "Ridibooks") {
             OutlinedTextField(
                value = cookieInput,
                onValueChange = {
                    cookieInput = it
                    prefs.edit().putString("ridi_cookie", it).apply()
                },
                label = { Text("Cookie (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
        }

        if (selectedSource == "KakaoPage") {
             OutlinedTextField(
                value = kakaoCookieInput,
                onValueChange = {
                    kakaoCookieInput = it
                    prefs.edit().putString("kakao_cookie", it).apply()
                },
                label = { Text("KakaoPage Cookie") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
        }

        HorizontalDivider()

        val defOut = prefs.getString("default_output_uri", null)
        if (defOut != null) {
            Text("Output: ${Uri.parse(defOut).lastPathSegment}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
             Text("Output: Default (App Storage)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoRetry, onCheckedChange = { autoRetry = it })
            Text("Auto Retry")
        }
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
            splitMode, { splitMode = it },
            lowRam, { lowRam = it },
            quality, { quality = it },
            currentTemplate,
            availableTemplates,
            ::applyTemplate, ::saveTemplate, ::deleteTemplate
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Queue (${queueItems.size})")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { expandedConcurrency = true }) { Text("Workers: $concurrencyCount") }
                    DropdownMenu(expanded = expandedConcurrency, onDismissRequest = { expandedConcurrency = false }) {
                        (1..5).forEach { num ->
                            DropdownMenuItem(text = { Text("$num") }, onClick = { concurrencyCount = num; expandedConcurrency = false })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                 Button(onClick = { scope.launch(Dispatchers.IO) { Python.getInstance().getModule("bato").callAttr("clear_completed", context.cacheDir.absolutePath) } }) { Text("Clear Done") }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { isProcessorRunning = !isProcessorRunning },
            colors = ButtonDefaults.buttonColors(containerColor = if(isProcessorRunning) Color.Red else Color(0xFF4CAF50))
        ) { Text(if (isProcessorRunning) "STOP QUEUE" else "START QUEUE") }

        LazyColumn(
            modifier = Modifier.height(400.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(queueItems) { item ->
                QueueItemRow(
                    item = item,
                    onDelete = { id -> scope.launch(Dispatchers.IO) { Python.getInstance().getModule("bato").callAttr("remove_from_queue", context.cacheDir.absolutePath, id) } },
                    onRetry = { id -> scope.launch(Dispatchers.IO) { Python.getInstance().getModule("bato").callAttr("retry_item", context.cacheDir.absolutePath, id) } },
                    onPause = { id -> scope.launch(Dispatchers.IO) { Python.getInstance().getModule("bato").callAttr("pause_item", context.cacheDir.absolutePath, id) } }
                )
            }
        }
    }
}

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
            val targetName = "$baseName.bmp"
            var targetFile = java.io.File(base, targetName)
            var index = 1
            while (targetFile.exists()) {
                targetFile = java.io.File(base, "${baseName}_webp$index.bmp")
                index += 1
            }
            val converted = ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                BitmapFactory.decodeStream(ins)
            }
            if (converted != null) {
                MainActivity.saveAsBmp(converted, targetFile)
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
            "webp" -> "image/webp"
            "png", "jpg", "jpeg", "bmp", "tiff", "tif", "tga" -> "image/*"
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
            if (existing != null) existing.delete()

            val target = parent.createFile(mime, file.name)!!
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { outs ->
                file.inputStream().use { ins -> ins.copyTo(outs) }
            }
        }
    }
    upload(src, destTree, mimeOverride)
}
