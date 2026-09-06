package com.astral.stitchapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NaturalOrderComparator : Comparator<File> {
    override fun compare(f1: File, f2: File): Int {
        val s1 = f1.name
        val s2 = f2.name
        var i1 = 0
        var i2 = 0
        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]
            if (c1.isDigit() && c2.isDigit()) {
                var num1Str = ""
                while (i1 < s1.length && s1[i1].isDigit()) {
                    num1Str += s1[i1]
                    i1++
                }
                var num2Str = ""
                while (i2 < s2.length && s2[i2].isDigit()) {
                    num2Str += s2[i2]
                    i2++
                }
                val n1 = num1Str.toLongOrNull() ?: Long.MAX_VALUE
                val n2 = num2Str.toLongOrNull() ?: Long.MAX_VALUE
                if (n1 != n2) {
                    return n1.compareTo(n2)
                }
            } else {
                val cmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (cmp != 0) return cmp
                i1++
                i2++
            }
        }
        return s1.length.compareTo(s2.length)
    }
}

class ProgressWriter(private val progressPath: String?, offset: Int = 0) {
    private var processed = offset
    private var total = maxOf(offset + 1, 1)

    init {
        write()
    }

    @Synchronized
    fun ensureTotal(desiredTotal: Int) {
        if (desiredTotal > total) {
            total = desiredTotal
            write()
        }
    }

    @Synchronized
    fun addTotal(delta: Int) {
        ensureTotal(total + delta)
    }

    @Synchronized
    fun step(inc: Int = 1) {
        processed += inc
        if (processed > total) {
            total = processed
        }
        write()
    }

    @Synchronized
    fun finish(markDone: Boolean = true) {
        processed = maxOf(processed, total)
        write(done = markDone)
    }

    private fun write(done: Boolean = false) {
        if (progressPath.isNullOrBlank()) return
        try {
            val file = File(progressPath)
            val json = JSONObject().apply {
                put("processed", processed)
                put("total", total)
                if (done) put("done", true)
            }
            file.writeText(json.toString())
        } catch (_: Exception) {}
    }
}

object SmartStitcher {

    @JvmStatic
    @JvmOverloads
    fun run(
        inputFolder: String,
        splitHeight: Int = 5000,
        outputFilesType: String = ".png",
        batchMode: Boolean = false,
        widthEnforceType: Int = 0,
        customWidth: Int = 720,
        sensitivity: Int = 90,
        ignorablePixels: Int = 0,
        scanLineStep: Int = 5,
        lowRam: Boolean = false,
        unitImages: Int = 20,
        outputFolder: String? = null,
        filenameTemplate: String? = null,
        zipOutput: Boolean = false,
        pdfOutput: Boolean = false,
        pdfPassword: String? = null,
        progressPath: String? = null,
        progressOffset: Int = 0,
        markDone: Boolean = true,
        splitMode: Int = 2,
        quality: Int = 100
    ): String = runBlocking {
        runAsync(
            inputFolder = inputFolder,
            splitHeight = splitHeight,
            outputFilesType = outputFilesType,
            batchMode = batchMode,
            widthEnforceType = widthEnforceType,
            customWidth = customWidth,
            sensitivity = sensitivity,
            ignorablePixels = ignorablePixels,
            scanLineStep = scanLineStep,
            lowRam = lowRam,
            unitImages = unitImages,
            outputFolder = outputFolder,
            filenameTemplate = filenameTemplate,
            zipOutput = zipOutput,
            pdfOutput = pdfOutput,
            pdfPassword = pdfPassword,
            progressPath = progressPath,
            progressOffset = progressOffset,
            markDone = markDone,
            splitMode = splitMode,
            quality = quality
        )
    }

    suspend fun runAsync(
        inputFolder: String,
        splitHeight: Int = 5000,
        outputFilesType: String = ".png",
        batchMode: Boolean = false,
        widthEnforceType: Int = 0,
        customWidth: Int = 720,
        sensitivity: Int = 90,
        ignorablePixels: Int = 0,
        scanLineStep: Int = 5,
        lowRam: Boolean = false,
        unitImages: Int = 20,
        outputFolder: String? = null,
        filenameTemplate: String? = null,
        zipOutput: Boolean = false,
        pdfOutput: Boolean = false,
        pdfPassword: String? = null,
        progressPath: String? = null,
        progressOffset: Int = 0,
        markDone: Boolean = true,
        splitMode: Int = 2,
        quality: Int = 100
    ): String {
        var finalOutType = outputFilesType
        var finalZip = zipOutput
        var finalPdf = pdfOutput
        if (finalOutType == ".webp") {
            finalOutType = ".bmp"
            finalZip = false
            finalPdf = false
        }

        val resolvedOutputFolder = resolveOutputFolder(inputFolder, outputFolder)
        val progressFile = progressPath ?: File(resolvedOutputFolder, "progress.json").absolutePath
        val writer = ProgressWriter(progressFile, progressOffset)

        val folderPaths = getFolderPaths(batchMode, inputFolder, resolvedOutputFolder)
        if (folderPaths.isEmpty()) {
            writer.finish(markDone)
            return resolvedOutputFolder
        }

        for ((inDirStr, outDirStr) in folderPaths) {
            val inDir = File(inDirStr)
            val outDir = File(outDirStr)
            if (!outDir.exists()) outDir.mkdirs()

            val parentFolderName = inDir.name.ifBlank { inDir.parentFile?.name ?: "Stitched" }

            if (lowRam) {
                var saveOffset = 0
                var nextOffset: Int? = 0
                var firstImage: Bitmap? = null

                while (true) {
                    writer.addTotal(4)
                    val (images, newNextOffset) = loadUnitImagesParallel(inDir, firstImage, nextOffset ?: 0, unitImages)
                    nextOffset = newNextOffset
                    writer.step()

                    if (images.isEmpty()) break

                    val helperResult = helperProcess(
                        images = images,
                        widthEnforceType = widthEnforceType,
                        customWidth = customWidth,
                        splitHeight = splitHeight,
                        sensitivity = sensitivity,
                        ignorablePixels = ignorablePixels,
                        scanLineStep = scanLineStep,
                        splitMode = splitMode,
                        progressWriter = writer
                    )

                    if (helperResult.isEmpty()) continue

                    if (helperResult.size > 1 && nextOffset != null) {
                        firstImage = helperResult.last()
                        val saveList = helperResult.subList(0, helperResult.size - 1)
                        writer.addTotal(saveList.size)
                        saveOffset = saveSlicesParallel(
                            slices = saveList,
                            outputFolder = outDir,
                            outputType = finalOutType,
                            filenameTemplate = filenameTemplate,
                            parentName = parentFolderName,
                            quality = quality,
                            startOffset = saveOffset,
                            progressWriter = writer
                        )
                    } else {
                        firstImage = null
                        writer.addTotal(helperResult.size)
                        saveOffset = saveSlicesParallel(
                            slices = helperResult,
                            outputFolder = outDir,
                            outputType = finalOutType,
                            filenameTemplate = filenameTemplate,
                            parentName = parentFolderName,
                            quality = quality,
                            startOffset = saveOffset,
                            progressWriter = writer
                        )
                    }

                    if (nextOffset == null) break
                }
            } else {
                writer.addTotal(4)
                val images = loadImagesParallel(inDir)
                writer.step()

                if (images.isNotEmpty()) {
                    val finalImages = helperProcess(
                        images = images,
                        widthEnforceType = widthEnforceType,
                        customWidth = customWidth,
                        splitHeight = splitHeight,
                        sensitivity = sensitivity,
                        ignorablePixels = ignorablePixels,
                        scanLineStep = scanLineStep,
                        splitMode = splitMode,
                        progressWriter = writer
                    )

                    if (finalImages.isNotEmpty()) {
                        writer.addTotal(finalImages.size)
                        saveSlicesParallel(
                            slices = finalImages,
                            outputFolder = outDir,
                            outputType = finalOutType,
                            filenameTemplate = filenameTemplate,
                            parentName = parentFolderName,
                            quality = quality,
                            startOffset = 0,
                            progressWriter = writer
                        )
                    }
                }
            }
        }

        writer.finish(markDone)

        val progFileInOut = File(resolvedOutputFolder, "progress.json")
        if (progFileInOut.exists()) {
            try { progFileInOut.delete() } catch (_: Exception) {}
        }

        if (finalZip) {
            return packZip(File(resolvedOutputFolder)).absolutePath
        }
        if (finalPdf) {
            return packPdf(File(resolvedOutputFolder), pdfPassword, quality).absolutePath
        }

        return resolvedOutputFolder
    }

    @JvmStatic
    fun packArchive(sourcePath: String, fmtName: String, pdfPassword: String? = null, quality: Int = 100): String {
        val file = File(sourcePath)
        if (!file.exists()) return sourcePath
        return when (fmtName.uppercase(Locale.ROOT)) {
            "ZIP" -> packZip(file).absolutePath
            "PDF" -> packPdf(file, pdfPassword, quality).absolutePath
            else -> sourcePath
        }
    }

    private fun helperProcess(
        images: List<Bitmap>,
        widthEnforceType: Int,
        customWidth: Int,
        splitHeight: Int,
        sensitivity: Int,
        ignorablePixels: Int,
        scanLineStep: Int,
        splitMode: Int,
        progressWriter: ProgressWriter
    ): List<Bitmap> {
        if (images.isEmpty()) return emptyList()

        val resized = resizeImages(images, widthEnforceType, customWidth)
        progressWriter.step()

        val combined = combineImages(resized)
        progressWriter.step()

        val finalImages = splitImage(
            combinedBitmap = combined,
            splitHeight = splitHeight,
            sensitivity = sensitivity,
            ignorablePixels = ignorablePixels,
            scanStep = scanLineStep,
            splitMode = splitMode
        )
        progressWriter.step()

        return finalImages
    }

    private fun resolveOutputFolder(inputFolder: String, outputFolder: String?): String {
        val inputAbs = File(inputFolder).absoluteFile
        if (!outputFolder.isNullOrBlank()) {
            return File(outputFolder).absolutePath
        }
        val parentDir = inputAbs.parentFile
        val folderName = inputAbs.name.ifBlank { parentDir?.name ?: "Folder" }
        return File(parentDir, "$folderName [Stitched]").absolutePath
    }

    private fun getFolderPaths(batchMode: Boolean, inputFolder: String, outputFolder: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val inAbs = File(inputFolder).absoluteFile
        val outAbs = File(outputFolder).absoluteFile

        if (!batchMode) {
            result.add(Pair(inAbs.absolutePath, outAbs.absolutePath))
        } else {
            val children = inAbs.listFiles() ?: arrayOf()
            for (child in children) {
                if (child.isDirectory) {
                    val outChild = File(outAbs, "${child.name} [Stitched]")
                    result.add(Pair(child.absolutePath, outChild.absolutePath))
                }
            }
        }
        return result
    }

    private suspend fun loadImagesParallel(folder: File): List<Bitmap> = coroutineScope {
        val files = folder.listFiles()?.filter { file ->
            val ext = file.extension.lowercase(Locale.ROOT)
            ext in setOf("png", "jpg", "jpeg", "jfif", "webp", "bmp", "tiff", "tif", "tga", "avif")
        }?.sortedWith(NaturalOrderComparator()) ?: listOf()

        val deferreds = files.map { file ->
            async(Dispatchers.IO) {
                decodeImageFile(file)
            }
        }

        deferreds.awaitAll().filterNotNull()
    }

    private suspend fun loadUnitImagesParallel(
        folder: File,
        firstImage: Bitmap?,
        offset: Int,
        unitLimit: Int
    ): Pair<List<Bitmap>, Int?> = coroutineScope {
        val result = mutableListOf<Bitmap>()
        if (firstImage != null) {
            result.add(firstImage)
        }

        val files = folder.listFiles()?.filter { file ->
            val ext = file.extension.lowercase(Locale.ROOT)
            ext in setOf("png", "jpg", "jpeg", "jfif", "webp", "bmp", "tiff", "tif", "tga", "avif")
        }?.sortedWith(NaturalOrderComparator()) ?: listOf()

        if (files.isEmpty()) {
            return@coroutineScope Pair(result, null)
        }

        val targetFiles = mutableListOf<File>()
        var loopCount = 0
        var imgCount = 0
        var last = false

        for (file in files) {
            loopCount++
            if (imgCount < unitLimit && loopCount > offset) {
                targetFiles.add(file)
                imgCount++
                last = true
            } else {
                last = false
            }
        }

        val deferreds = targetFiles.map { file ->
            async(Dispatchers.IO) {
                decodeImageFile(file)
            }
        }
        val decoded = deferreds.awaitAll().filterNotNull()
        result.addAll(decoded)

        val nextOffset = if (result.size >= unitLimit && !last) {
            offset + unitLimit
        } else {
            null
        }

        Pair(result, nextOffset)
    }

    private fun decodeImageFile(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractSlice(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val slice = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val chunkSize = 1000
        var currY = 0
        while (currY < height) {
            val chunkH = minOf(chunkSize, height - currY)
            val buffer = IntArray(width * chunkH)
            source.getPixels(buffer, 0, width, x, y + currY, width, chunkH)
            slice.setPixels(buffer, 0, width, 0, currY, width, chunkH)
            currY += chunkH
        }
        return slice
    }

    private fun resizeImages(images: List<Bitmap>, widthEnforceType: Int, customWidth: Int): List<Bitmap> {
        if (widthEnforceType == 0 || images.isEmpty()) return images

        val targetWidth = when (widthEnforceType) {
            1 -> images.minOf { it.width }
            2 -> customWidth
            else -> return images
        }

        return images.map { img ->
            if (img.width == targetWidth) {
                img
            } else {
                val ratio = img.height.toDouble() / img.width.toDouble()
                val targetHeight = (ratio * targetWidth).toInt()
                if (targetHeight <= 0) return@map img

                if (img.height <= 30000) {
                    val resized = Bitmap.createScaledBitmap(img, targetWidth, targetHeight, true)
                    if (resized != img) {
                        img.recycle()
                    }
                    resized
                } else {
                    val resized = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val maxSrcChunk = 20000
                    var srcY = 0
                    while (srcY < img.height) {
                        val srcChunkH = minOf(maxSrcChunk, img.height - srcY)
                        val dstY = ((srcY.toDouble() / img.height) * targetHeight).toInt()
                        val dstYEnd = (((srcY + srcChunkH).toDouble() / img.height) * targetHeight).toInt().coerceAtMost(targetHeight)
                        val dstChunkH = dstYEnd - dstY
                        if (dstChunkH > 0) {
                            val srcChunk = extractSlice(img, 0, srcY, img.width, srcChunkH)
                            val scaledChunk = Bitmap.createScaledBitmap(srcChunk, targetWidth, dstChunkH, true)
                            if (scaledChunk != srcChunk) srcChunk.recycle()

                            var cY = 0
                            val cChunkSize = 1000
                            while (cY < dstChunkH) {
                                val cH = minOf(cChunkSize, dstChunkH - cY)
                                val buf = IntArray(targetWidth * cH)
                                scaledChunk.getPixels(buf, 0, targetWidth, 0, cY, targetWidth, cH)
                                resized.setPixels(buf, 0, targetWidth, 0, dstY + cY, targetWidth, cH)
                                cY += cH
                            }
                            scaledChunk.recycle()
                        }
                        srcY += srcChunkH
                    }
                    img.recycle()
                    resized
                }
            }
        }
    }

    private fun combineImages(images: List<Bitmap>): Bitmap {
        val maxWidth = images.maxOf { it.width }
        val totalHeight = images.sumOf { it.height }

        val combined = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)

        val maxChunkH = 1000
        val buffer = IntArray(maxWidth * maxChunkH)

        var currentY = 0
        for (img in images) {
            val w = img.width
            val h = img.height
            var imgY = 0
            while (imgY < h) {
                val chunkH = minOf(maxChunkH, h - imgY)
                if (w < maxWidth) {
                    buffer.fill(Color.WHITE, 0, maxWidth * chunkH)
                }
                img.getPixels(buffer, 0, maxWidth, 0, imgY, w, chunkH)
                combined.setPixels(buffer, 0, maxWidth, 0, currentY + imgY, maxWidth, chunkH)
                imgY += chunkH
            }
            currentY += h
            img.recycle()
        }
        return combined
    }

    private fun splitImage(
        combinedBitmap: Bitmap,
        splitHeight: Int,
        sensitivity: Int,
        ignorablePixels: Int,
        scanStep: Int,
        splitMode: Int
    ): List<Bitmap> {
        val maxHeight = combinedBitmap.height
        val maxWidth = combinedBitmap.width
        val result = mutableListOf<Bitmap>()
        var splitOffset = 0
        val rowBuffer = IntArray(maxWidth)

        while (splitOffset + splitHeight < maxHeight) {
            val newSplitHeight = when (splitMode) {
                1 -> splitHeight
                2 -> adjustSplitLocation2D(
                    combinedBitmap = combinedBitmap,
                    splitHeight = splitHeight,
                    splitOffset = splitOffset,
                    sensitivity = sensitivity,
                    ignorablePixels = ignorablePixels,
                    scanStep = scanStep,
                    rowBuffer = rowBuffer
                )
                else -> adjustSplitLocation(
                    combinedBitmap = combinedBitmap,
                    splitHeight = splitHeight,
                    splitOffset = splitOffset,
                    sensitivity = sensitivity,
                    ignorablePixels = ignorablePixels,
                    scanStep = scanStep,
                    rowBuffer = rowBuffer
                )
            }

            val slice = extractSlice(combinedBitmap, 0, splitOffset, maxWidth, newSplitHeight)
            result.add(slice)
            splitOffset += newSplitHeight
        }

        val remainingRows = maxHeight - splitOffset
        if (remainingRows > 0) {
            val slice = extractSlice(combinedBitmap, 0, splitOffset, maxWidth, remainingRows)
            result.add(slice)
        }

        combinedBitmap.recycle()
        return result
    }

    private fun adjustSplitLocation2D(
        combinedBitmap: Bitmap,
        splitHeight: Int,
        splitOffset: Int,
        sensitivity: Int,
        ignorablePixels: Int,
        scanStep: Int,
        rowBuffer: IntArray
    ): Int {
        val threshold = (255 * (1.0 - (sensitivity / 100.0))).toInt().coerceAtLeast(5)
        val maxHeight = combinedBitmap.height
        val maxWidth = combinedBitmap.width
        val window = 12
        val winHeight = window * 2 + 1
        val winBuffer = IntArray(winHeight * maxWidth)

        var bestHeight = splitHeight
        var minPenalty = Double.MAX_VALUE

        val minH = (0.4 * splitHeight).toInt().coerceAtLeast(1)
        val maxH = (1.3 * splitHeight).toInt().coerceAtMost(maxHeight - splitOffset - 1)

        val candidateHeights = mutableListOf<Int>()
        var h = splitHeight
        while (h >= minH) {
            candidateHeights.add(h)
            h -= scanStep
        }
        h = splitHeight + scanStep
        while (h <= maxH) {
            candidateHeights.add(h)
            h += scanStep
        }

        for (candH in candidateHeights) {
            val splitRow = splitOffset + candH
            if (splitRow < window || splitRow >= maxHeight - window) continue

            val penalty = evaluateRow2DPenalty(
                combinedBitmap = combinedBitmap,
                splitRow = splitRow,
                window = window,
                threshold = threshold,
                ignorablePixels = ignorablePixels,
                winBuffer = winBuffer,
                maxWidth = maxWidth,
                currentMinPenalty = minPenalty
            )

            if (penalty == 0.0) {
                return candH
            }

            if (penalty < minPenalty) {
                minPenalty = penalty
                bestHeight = candH
            }
        }

        if (minPenalty < threshold * 10.0) {
            return bestHeight
        }

        return adjustSplitLocation(
            combinedBitmap = combinedBitmap,
            splitHeight = splitHeight,
            splitOffset = splitOffset,
            sensitivity = sensitivity,
            ignorablePixels = ignorablePixels,
            scanStep = scanStep,
            rowBuffer = rowBuffer
        )
    }

    private fun evaluateRow2DPenalty(
        combinedBitmap: Bitmap,
        splitRow: Int,
        window: Int,
        threshold: Int,
        ignorablePixels: Int,
        winBuffer: IntArray,
        maxWidth: Int,
        currentMinPenalty: Double
    ): Double {
        val winHeight = window * 2 + 1
        val startY = splitRow - window
        combinedBitmap.getPixels(winBuffer, 0, maxWidth, 0, startY, maxWidth, winHeight)

        val startX = ignorablePixels.coerceIn(0, maxWidth - 1)
        val endX = (maxWidth - ignorablePixels - 1).coerceIn(startX, maxWidth - 1)
        if (startX >= endX) return 0.0

        val centerRowOffset = window * maxWidth
        var totalPenalty = 0.0

        var prevLum = getLuminance(winBuffer[centerRowOffset + startX])
        for (x in (startX + 1)..endX) {
            val curLum = getLuminance(winBuffer[centerRowOffset + x])
            val diff = Math.abs(curLum - prevLum)
            if (diff > threshold) {
                totalPenalty += (diff - threshold) * 10.0
                if (totalPenalty >= currentMinPenalty) return totalPenalty
            }
            prevLum = curLum
        }

        val xStep = if (maxWidth > 1000) 3 else 2
        for (x in startX..endX step xStep) {
            var prevVertLum = getLuminance(winBuffer[x])
            for (r in 1 until winHeight) {
                val curVertLum = getLuminance(winBuffer[r * maxWidth + x])
                val vDiff = Math.abs(curVertLum - prevVertLum)
                if (vDiff > threshold) {
                    totalPenalty += (vDiff - threshold) * 5.0
                }
                prevVertLum = curVertLum
            }
            if (totalPenalty >= currentMinPenalty) return totalPenalty
        }

        val leftGutterLum = getLuminance(winBuffer[centerRowOffset + startX])
        val rightGutterLum = getLuminance(winBuffer[centerRowOffset + endX])
        val midX = (startX + endX) / 2
        val midLum = getLuminance(winBuffer[centerRowOffset + midX])

        val gutterDiff = Math.abs(leftGutterLum - rightGutterLum)
        val centerGutterDiff = Math.abs(midLum - leftGutterLum)

        if (gutterDiff > threshold) {
            totalPenalty += (gutterDiff - threshold) * 3.0
        }
        if (centerGutterDiff > threshold) {
            totalPenalty += (centerGutterDiff - threshold) * 3.0
        }

        return totalPenalty
    }

    private fun adjustSplitLocation(
        combinedBitmap: Bitmap,
        splitHeight: Int,
        splitOffset: Int,
        sensitivity: Int,
        ignorablePixels: Int,
        scanStep: Int,
        rowBuffer: IntArray
    ): Int {
        val threshold = (255 * (1.0 - (sensitivity / 100.0))).toInt()
        var newSplitHeight = splitHeight
        val maxHeight = combinedBitmap.height
        val maxWidth = combinedBitmap.width
        val lastRow = maxHeight - 1
        var adjustInProgress = true
        var countdown = true

        while (adjustInProgress) {
            adjustInProgress = false
            val splitRow = splitOffset + newSplitHeight
            if (splitRow > lastRow) break

            combinedBitmap.getPixels(rowBuffer, 0, maxWidth, 0, splitRow, maxWidth, 1)

            val startX = ignorablePixels
            val endX = maxWidth - ignorablePixels - 1

            if (startX <= endX) {
                var prevPixel = getLuminance(rowBuffer[startX])
                for (x in (startX + 1)..endX) {
                    val currentPixel = getLuminance(rowBuffer[x])
                    val diff = currentPixel - prevPixel
                    if (diff < -threshold || diff > threshold) {
                        if (countdown) {
                            newSplitHeight -= scanStep
                        } else {
                            newSplitHeight += scanStep
                        }
                        adjustInProgress = true
                        break
                    }
                    prevPixel = currentPixel
                }
            }

            if (newSplitHeight < (0.4 * splitHeight).toInt()) {
                newSplitHeight = splitHeight
                countdown = false
                adjustInProgress = true
            }
        }
        return newSplitHeight
    }

    private inline fun getLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private suspend fun saveSlicesParallel(
        slices: List<Bitmap>,
        outputFolder: File,
        outputType: String,
        filenameTemplate: String?,
        parentName: String,
        quality: Int,
        startOffset: Int,
        progressWriter: ProgressWriter?
    ): Int = coroutineScope {
        val ext = outputType.removePrefix(".")
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val timeStr = SimpleDateFormat("HHmmss", Locale.ROOT).format(Date())

        val deferreds = slices.mapIndexed { idx, sliceBitmap ->
            val imageIndex = startOffset + idx + 1
            val filename = buildFilename(imageIndex, ext, filenameTemplate, parentName, dateStr, timeStr)
            val outFile = File(outputFolder, filename)

            async(Dispatchers.IO) {
                saveSingleBitmap(sliceBitmap, outFile, outputType, quality)
                progressWriter?.step()
            }
        }

        deferreds.awaitAll()

        // Set sequential timestamps so files maintain strict order when sorted by date
        val baseTime = System.currentTimeMillis() - (slices.size * 1000L)
        slices.indices.forEach { idx ->
            val imageIndex = startOffset + idx + 1
            val filename = buildFilename(imageIndex, ext, filenameTemplate, parentName, dateStr, timeStr)
            val outFile = File(outputFolder, filename)
            if (outFile.exists()) {
                outFile.setLastModified(baseTime + (idx * 1000L))
            }
        }

        slices.forEach { it.recycle() }
        startOffset + slices.size
    }

    private fun saveSingleBitmap(bitmap: Bitmap, file: File, outputType: String, quality: Int) {
        when (outputType.lowercase(Locale.ROOT)) {
            ".bmp" -> MainActivity.saveAsBmp(bitmap, file)
            ".jpg", ".jpeg" -> {
                file.outputStream().buffered().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                }
            }
            ".webp" -> {
                file.outputStream().buffered().use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        if (quality >= 100) {
                            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                        } else {
                            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.coerceIn(1, 100), out)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), out)
                    }
                }
            }
            else -> { // .png
                file.outputStream().buffered().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
    }

    private fun buildFilename(
        idx: Int,
        ext: String,
        template: String?,
        parentName: String,
        dateStr: String,
        timeStr: String
    ): String {
        val cleanExt = ext.removePrefix(".")
        val tmpl = if (!template.isNullOrBlank()) template else "{num}.{ext}"
        return tmpl
            .replace("{num}", String.format(Locale.ROOT, "%02d", idx))
            .replace("{ext}", cleanExt)
            .replace("{parent}", parentName)
            .replace("{time}", timeStr)
            .replace("{date}", dateStr)
            .replace("{char}", indexToLetters(idx))
    }

    private fun indexToLetters(idx: Int): String {
        var num = idx
        val result = StringBuilder()
        while (num > 0) {
            num--
            val rem = num % 26
            result.append(('a'.code + rem).toChar())
            num /= 26
        }
        return if (result.isEmpty()) "a" else result.reverse().toString()
    }

    private fun packZip(sourceDir: File): File {
        val zipFile = File(sourceDir.parentFile, "${sourceDir.name}.zip")
        if (zipFile.exists()) zipFile.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val files = sourceDir.listFiles() ?: arrayOf()
            files.sortWith(NaturalOrderComparator())
            for (file in files) {
                if (file.isFile) {
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().buffered().use { ins ->
                        ins.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
        sourceDir.deleteRecursively()
        return zipFile
    }

    private fun packPdf(sourceDir: File, pdfPassword: String? = null, quality: Int = 100): File {
        val pdfFile = File(sourceDir.parentFile, "${sourceDir.name}.pdf")
        if (pdfFile.exists()) pdfFile.delete()

        val files = sourceDir.listFiles()?.filter { f ->
            val ext = f.extension.lowercase(Locale.ROOT)
            ext in setOf("png", "jpg", "jpeg", "jfif", "webp", "bmp", "tiff", "tif", "tga", "avif")
        }?.sortedWith(NaturalOrderComparator()) ?: listOf()

        if (files.isEmpty()) return sourceDir

        val pdDoc = com.tom_roush.pdfbox.pdmodel.PDDocument()
        try {
            for (imgFile in files) {
                val ext = imgFile.extension.lowercase(Locale.ROOT)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imgFile.absolutePath, options)
                val width = options.outWidth
                val height = options.outHeight
                if (width <= 0 || height <= 0) continue

                val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                    com.tom_roush.pdfbox.pdmodel.common.PDRectangle(width.toFloat(), height.toFloat())
                )
                pdDoc.addPage(page)

                val pdImage = if (ext == "jpg" || ext == "jpeg" || ext == "jfif") {
                    imgFile.inputStream().use { ins ->
                        com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(pdDoc, ins)
                    }
                } else {
                    val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath) ?: continue
                    try {
                        val baos = ByteArrayOutputStream()
                        val compQuality = if (quality in 1..100) quality else 90
                        bitmap.compress(Bitmap.CompressFormat.JPEG, compQuality, baos)
                        ByteArrayInputStream(baos.toByteArray()).use { ins ->
                            com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(pdDoc, ins)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdDoc, page).use { contentStream ->
                    contentStream.drawImage(pdImage, 0f, 0f, width.toFloat(), height.toFloat())
                }
            }

            if (!pdfPassword.isNullOrBlank()) {
                val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission().apply {
                    setCanPrint(true)
                    setCanExtractContent(true)
                }
                val spp = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(pdfPassword, pdfPassword, ap)
                spp.encryptionKeyLength = 128
                pdDoc.protect(spp)
            }

            pdDoc.save(pdfFile)
        } finally {
            pdDoc.close()
        }

        sourceDir.deleteRecursively()
        return pdfFile
    }
}
