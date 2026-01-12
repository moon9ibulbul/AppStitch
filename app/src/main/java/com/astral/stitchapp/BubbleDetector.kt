package com.astral.stitchapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class BubbleDetector(modelPath: String) {
    private val env: OrtEnvironment
    private val session: OrtSession

    init {
        Log.d("BubbleDetector", "Initializing with model: $modelPath")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        session = env.createSession(modelPath, opts)
        Log.d("BubbleDetector", "Session created successfully")
    }

    // Returns list of [y_min, y_max]
    fun detect(imageBytes: ByteArray): Array<IntArray> {
        val st = System.currentTimeMillis()
        val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (originalBitmap == null) {
            Log.e("BubbleDetector", "Failed to decode bitmap from bytes")
            return emptyArray()
        }

        val originalW = originalBitmap.width
        val originalH = originalBitmap.height

        // 1. Preprocess
        val inputW = 640
        val inputH = 640
        val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, inputW, inputH, true)

        val pixels = IntArray(inputW * inputH)
        resizedBitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

        // Use Direct ByteBuffer for ORT
        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * inputW * inputH * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Extract pixels and normalize (CHW layout, 1/255.0)
        // R channel
        for (i in 0 until inputW * inputH) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
        }
        // G channel
        for (i in 0 until inputW * inputH) {
            val pixel = pixels[i]
            floatBuffer.put(inputW * inputH + i, ((pixel shr 8) and 0xFF) / 255.0f)
        }
        // B channel
        for (i in 0 until inputW * inputH) {
            val pixel = pixels[i]
            floatBuffer.put(2 * inputW * inputH + i, (pixel and 0xFF) / 255.0f)
        }
        floatBuffer.rewind()

        // 2. Inference
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
        val tensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        val output = session.run(Collections.singletonMap(inputName, tensor))
        // Output shape is (1, 5, 8400) -> [cx, cy, w, h, score]
        val rawOutput = output[0].value as Array<Array<FloatArray>>
        val predictions = rawOutput[0] // 5 x 8400
        val numProposals = predictions[0].size // 8400

        val boxes = ArrayList<Box>()
        val scoreThreshold = 0.25f

        val xScale = originalW.toFloat() / inputW
        val yScale = originalH.toFloat() / inputH

        for (i in 0 until numProposals) {
            val score = predictions[4][i]
            if (score > scoreThreshold) {
                val cx = predictions[0][i]
                val cy = predictions[1][i]
                val w = predictions[2][i]
                val h = predictions[3][i]

                val x = (cx - w / 2) * xScale
                val y = (cy - h / 2) * yScale
                val wScaled = w * xScale
                val hScaled = h * yScale

                boxes.add(Box(x, y, wScaled, hScaled, score))
            }
        }

        // 3. NMS
        val keptBoxes = nms(boxes, 0.45f)

        // 4. Return ranges
        val ranges = keptBoxes.map {
            val yMin = it.y
            val yMax = it.y + it.h
            val yMinInt = yMin.toInt().coerceAtLeast(0)
            val yMaxInt = yMax.toInt().coerceAtMost(originalH)
            intArrayOf(yMinInt, yMaxInt)
        }.toTypedArray()

        Log.d("BubbleDetector", "Detected ${ranges.size} bubbles in ${System.currentTimeMillis() - st}ms")

        tensor.close()
        output.close()

        if (resizedBitmap != originalBitmap) resizedBitmap.recycle()
        originalBitmap.recycle()

        return ranges
    }

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float)

    private fun nms(boxes: List<Box>, threshold: Float): List<Box> {
        if (boxes.isEmpty()) return emptyList()

        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val selected = ArrayList<Box>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(best, other) > threshold) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)

        val interW = (x2 - x1).coerceAtLeast(0f)
        val interH = (y2 - y1).coerceAtLeast(0f)
        val interArea = interW * interH

        val areaA = a.w * a.h
        val areaB = b.w * b.h

        return interArea / (areaA + areaB - interArea)
    }
}
