package com.astral.stitchapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BubbleDetector(modelPath: String) {
    private val env: OrtEnvironment
    private val session: OrtSession

    companion object {
        private const val INPUT_SIZE = 640
        private const val STRIDE = 512
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val BOX_SCALE = 0.75f
        private const val TOUCHING_TOLERANCE_PX = 15f
        private const val ALIGNMENT_OVERLAP_RATIO = 0.5f
    }

    init {
        Log.d("BubbleDetector", "Initializing with model: $modelPath")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setInterOpNumThreads(4)
            opts.setIntraOpNumThreads(4)
        } catch (e: Exception) {
            Log.w("BubbleDetector", "Failed to set advanced opts", e)
        }
        session = env.createSession(modelPath, opts)
        Log.d("BubbleDetector", "Session created successfully. Inputs: ${session.inputNames}")
    }

    // Returns list of [y_min, y_max]
    fun detect(imageBytes: ByteArray): Array<IntArray> {
        val st = System.currentTimeMillis()

        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)

        if (originalBitmap == null) {
            Log.e("BubbleDetector", "Failed to decode bitmap from bytes")
            return emptyArray()
        }

        var procBitmap: Bitmap? = null

        try {
            val originalWidth = originalBitmap.width
            val originalHeight = originalBitmap.height

            // 1. Scale Logic (From Reference)
            // Resize to width 640 while maintaining aspect ratio
            val scaleFactor = if (originalWidth > INPUT_SIZE) INPUT_SIZE.toFloat() / originalWidth else 1.0f

            procBitmap = if (scaleFactor < 1.0f) {
                val targetHeight = (originalHeight * scaleFactor).roundToInt()
                Bitmap.createScaledBitmap(originalBitmap, INPUT_SIZE, targetHeight, true)
            } else {
                originalBitmap // No need to copy if not resizing, just treat as procBitmap
            }

            val width = procBitmap.width
            val height = procBitmap.height

            // Determine input names
            val inputNames = session.inputNames
            val imageInputName = inputNames.find { it.contains("image", ignoreCase = true) } ?: "images"
            val sizeInputName = inputNames.find { it.contains("size", ignoreCase = true) || it.contains("orig", ignoreCase = true) }

            // 2. Tiled Processing
            val allDetections = ArrayList<RectF>()

            var y = 0
            while (y < height) {
                var actualY = y
                if (height >= INPUT_SIZE) {
                    if (actualY + INPUT_SIZE > height) actualY = height - INPUT_SIZE
                } else {
                    actualY = 0
                }

                var x = 0
                while (x < width) {
                    var actualX = x
                    if (width >= INPUT_SIZE) {
                        if (actualX + INPUT_SIZE > width) actualX = width - INPUT_SIZE
                    } else {
                        actualX = 0
                    }

                    // Process Tile
                    val tileDetections = processTile(procBitmap, actualX, actualY, width, height, imageInputName, sizeInputName)
                    allDetections.addAll(tileDetections)

                    if (actualX + INPUT_SIZE >= width) break
                    x += STRIDE
                }
                if (actualY + INPUT_SIZE >= height) break
                y += STRIDE
            }

            // 3. NMS (Global)
            val nmsResults = nms(allDetections, IOU_THRESHOLD)

            // 4. Merge Touching Boxes (from Reference)
            val mergedBoxes = mergeTouchingBoxes(nmsResults)

            // 5. Map back to original coordinates & Shrink
            val ranges = mergedBoxes.map { box ->
                // Map back to original scale
                val originalBox = RectF(
                    box.left / scaleFactor,
                    box.top / scaleFactor,
                    box.right / scaleFactor,
                    box.bottom / scaleFactor
                )

                val cx = originalBox.centerX()
                val cy = originalBox.centerY()
                val w = originalBox.width() * BOX_SCALE
                val h = originalBox.height() * BOX_SCALE

                val yMin = (cy - h / 2).toInt().coerceAtLeast(0)
                val yMax = (cy + h / 2).toInt().coerceAtMost(originalHeight)

                intArrayOf(yMin, yMax)
            }.toTypedArray()

            Log.d("BubbleDetector", "Detected ${ranges.size} bubbles in ${System.currentTimeMillis() - st}ms")
            return ranges

        } finally {
            if (procBitmap != null && procBitmap != originalBitmap) {
                procBitmap.recycle()
            }
            originalBitmap.recycle()
        }
    }

    private fun processTile(
        sourceBitmap: Bitmap,
        x: Int, y: Int,
        totalW: Int, totalH: Int,
        imageInputName: String,
        sizeInputName: String?
    ): List<RectF> {
        val tileBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tileBitmap)

        // Draw crop
        val srcRect = Rect(x, y, min(x + INPUT_SIZE, totalW), min(y + INPUT_SIZE, totalH))
        val dstRect = Rect(0, 0, srcRect.width(), srcRect.height())
        canvas.drawBitmap(sourceBitmap, srcRect, dstRect, null)

        // Prepare Image Buffer
        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * INPUT_SIZE * INPUT_SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        tileBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // CHW, 0-1
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
        }
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            val pixel = pixels[i]
            floatBuffer.put(INPUT_SIZE * INPUT_SIZE + i, ((pixel shr 8) and 0xFF) / 255.0f)
        }
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            val pixel = pixels[i]
            floatBuffer.put(2 * INPUT_SIZE * INPUT_SIZE + i, (pixel and 0xFF) / 255.0f)
        }
        floatBuffer.rewind()

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val imageTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        // Prepare Inputs Map
        val inputs = HashMap<String, OnnxTensor>()
        inputs[imageInputName] = imageTensor

        // Handle Optional Size Tensor (for RT-DETR/Reference compatibility)
        var sizeTensor: OnnxTensor? = null
        if (sizeInputName != null) {
             val sizeBuffer = LongBuffer.allocate(2)
             sizeBuffer.put(INPUT_SIZE.toLong())
             sizeBuffer.put(INPUT_SIZE.toLong())
             sizeBuffer.flip()
             sizeTensor = OnnxTensor.createTensor(env, sizeBuffer, longArrayOf(1, 2))
             inputs[sizeInputName] = sizeTensor
        }

        val results = ArrayList<RectF>()
        try {
            val output = session.run(inputs)

            // PARSE OUTPUTS dynamically (YOLO vs RT-DETR/Reference)
            // YOLO: 1 output, shape [1, 5, 8400]
            // Reference: 3 outputs (boxes, scores, labels)

            val rawOutput = output.iterator().next().value.value // First output

            if (output.size() == 1 && rawOutput is Array<*>) {
                // YOLO Logic
                val rawArr = rawOutput as Array<Array<FloatArray>>
                val predictions = rawArr[0] // 5 x 8400
                val numProposals = predictions[0].size

                for (i in 0 until numProposals) {
                    val score = predictions[4][i]
                    if (score > CONFIDENCE_THRESHOLD) {
                        val cx = predictions[0][i]
                        val cy = predictions[1][i]
                        val w = predictions[2][i]
                        val h = predictions[3][i]

                        val globalCx = cx + x
                        val globalCy = cy + y

                        val xMin = globalCx - w / 2
                        val yMin = globalCy - h / 2
                        val xMax = globalCx + w / 2
                        val yMax = globalCy + h / 2

                        results.add(RectF(xMin, yMin, xMax, yMax))
                    }
                }
            } else {
                // Try Reference Logic (Boxes + Scores separate)
                // We iterate all outputs to find boxes and scores
                var boxes: Array<FloatArray>? = null
                var scores: FloatArray? = null

                for (entry in output) {
                    val value = entry.value.value
                    if (value is Array<*>) {
                         // [1, N, 4] -> Boxes
                         if (value.isNotEmpty() && value[0] is Array<*>) {
                             val first = value[0] as Array<FloatArray>
                             if (first.isNotEmpty() && first[0].size == 4) {
                                 boxes = first
                             }
                         }
                    } else if (value is FloatArray) { // Possible flattened score? usually [1, N] -> Array<FloatArray>??
                         // Wait, OnnxRuntime Java behavior for Float[1][N] is Array<FloatArray>
                         // If it's 1D, it's FloatArray.
                         // But usually scores are [Batch, N].
                         scores = value
                    } else if (value is Array<*>) {
                        // Check for scores as [1, N] -> Array<FloatArray> where inner is size N? No inner is size 1?
                        // Actually: Float[1][N] -> Object[] containing float[]?
                        // Let's assume standard layout.
                        if (value.isNotEmpty() && value[0] is FloatArray) {
                            val fArr = value[0] as FloatArray
                            // Distinguish from Boxes (Nx4)
                            // If this is Nx? No, it's 1 array of size N.
                            // Boxes was Array<FloatArray> (N arrays of size 4).
                            // This is just 1 array.
                            scores = fArr
                        }
                    }
                }

                if (boxes != null && scores != null) {
                    val numBoxes = boxes.size
                    for (i in 0 until numBoxes) {
                        val score = scores[i]
                        if (score > CONFIDENCE_THRESHOLD) {
                            val box = boxes[i]
                            // Reference: box is [x1, y1, x2, y2]
                            val bX1 = box[0]
                            val bY1 = box[1]
                            val bX2 = box[2]
                            val bY2 = box[3]

                            // Offset by tile
                            val xMin = bX1 + x
                            val yMin = bY1 + y
                            val xMax = bX2 + x
                            val yMax = bY2 + y

                            results.add(RectF(xMin, yMin, xMax, yMax))
                        }
                    }
                }
            }
            output.close()
        } catch (e: Exception) {
            Log.e("BubbleDetector", "Tile inference error", e)
        } finally {
            imageTensor.close()
            sizeTensor?.close()
            tileBitmap.recycle()
        }
        return results
    }

    private fun nms(boxes: List<RectF>, threshold: Float): List<RectF> {
        val sorted = boxes.sortedByDescending { it.width() * it.height() }.toMutableList()
        val selected = ArrayList<RectF>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(best, other) > threshold || ios(other, best) > 0.85f) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun mergeTouchingBoxes(initialBoxes: List<RectF>): List<RectF> {
        val boxes = initialBoxes.toMutableList()
        var merged = true

        while (merged) {
            merged = false
            var i = 0
            while (i < boxes.size) {
                val boxA = boxes[i]
                var j = i + 1
                while (j < boxes.size) {
                    val boxB = boxes[j]
                    if (shouldMerge(boxA, boxB)) {
                        boxA.union(boxB)
                        boxes.removeAt(j)
                        merged = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return boxes
    }

    private fun shouldMerge(a: RectF, b: RectF): Boolean {
         val vertGap = if (a.bottom < b.top) b.top - a.bottom else if (b.bottom < a.top) a.top - b.bottom else -1f
         val hOverlapStart = max(a.left, b.left)
         val hOverlapEnd = min(a.right, b.right)
         val hOverlapLen = hOverlapEnd - hOverlapStart
         val minWidth = min(a.width(), b.width())
         val isVertAligned = (hOverlapLen > 0) && (hOverlapLen / minWidth > ALIGNMENT_OVERLAP_RATIO)

         if (isVertAligned && vertGap <= TOUCHING_TOLERANCE_PX && vertGap > -TOUCHING_TOLERANCE_PX) return true

         val horzGap = if (a.right < b.left) b.left - a.right else if (b.right < a.left) a.left - b.right else -1f
         val vOverlapStart = max(a.top, b.top)
         val vOverlapEnd = min(a.bottom, b.bottom)
         val vOverlapLen = vOverlapEnd - vOverlapStart
         val minHeight = min(a.height(), b.height())
         val isHorzAligned = (vOverlapLen > 0) && (vOverlapLen / minHeight > ALIGNMENT_OVERLAP_RATIO)

         if (isHorzAligned && horzGap <= TOUCHING_TOLERANCE_PX && horzGap > -TOUCHING_TOLERANCE_PX) return true

         return false
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right < left || bottom < top) return 0f
        val intersection = (right - left) * (bottom - top)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        return intersection / (areaA + areaB - intersection)
    }

    private fun ios(inner: RectF, outer: RectF): Float {
        val left = max(inner.left, outer.left)
        val top = max(inner.top, outer.top)
        val right = min(inner.right, outer.right)
        val bottom = min(inner.bottom, outer.bottom)
        if (right < left || bottom < top) return 0f
        val intersection = (right - left) * (bottom - top)
        val innerArea = inner.width() * inner.height()
        if (innerArea <= 0) return 0f
        return intersection / innerArea
    }
}
