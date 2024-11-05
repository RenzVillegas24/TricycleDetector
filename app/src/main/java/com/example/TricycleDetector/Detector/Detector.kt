package com.example.TricycleDetector.Detector

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

open class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?, // Made optional to maintain compatibility
    val detectorListener: DetectorListener
) {
    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()
    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    // Make IOU threshold mutable
    private var iouThreshold = DEFAULT_IOU_THRESHOLD

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
        textSize = 32f
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val DEFAULT_IOU_THRESHOLD = 0.7f
    }

    fun setIouThreshold(threshold: Float) {
        require(threshold in 0f..1f) { "IOU threshold must be between 0 and 1" }
        iouThreshold = threshold
    }

    fun getIouThreshold(): Float = iouThreshold

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        setup()
        loadLabels()
    }

    private fun loadLabels() {
        try {
            labelPath?.let {
                labels.clear()
                labels.addAll(FileUtil.loadLabels(context, it))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setup() {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = getOptimalInterpreterOptions()
            interpreter = Interpreter(model, options)

            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()

            tensorWidth = inputShape?.get(1) ?: 0
            tensorHeight = inputShape?.get(2) ?: 0
            numChannel = outputShape?.get(1) ?: 0
            numElements = outputShape?.get(2) ?: 0

        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            Log.e("TFLite", "GPU delegation failed, falling back to CPU", e)
            setupCPUOnly()
        }
    }

    private fun setupCPUOnly() {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(model, options)

            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()

            tensorWidth = inputShape?.get(1) ?: 0
            tensorHeight = inputShape?.get(2) ?: 0
            numChannel = outputShape?.get(1) ?: 0
            numElements = outputShape?.get(2) ?: 0
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getOptimalInterpreterOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            val numCores = Runtime.getRuntime().availableProcessors()
            numThreads = minOf(4, numCores)

            try {
                val gpuDelegate = GpuDelegate(
                    GpuDelegateFactory.Options().apply {
                        setPrecisionLossAllowed(true)
                        setQuantizedModelsAllowed(true)
                    }
                )
                addDelegate(gpuDelegate)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    val nnapiDelegate = NnApiDelegate(
                        NnApiDelegate.Options().apply {
                            setUseNnapiCpu(true)
                            setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER)
                        }
                    )
                    addDelegate(nnapiDelegate)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    open fun detect(frame: Bitmap, outputBitmap: ((Bitmap) -> Unit)? = null) {
        backgroundExecutor.execute {
            detectInternal(frame) { processedBitmap ->
                outputBitmap?.invoke(processedBitmap)
            }
        }
    }

    private fun detectInternal(frame: Bitmap, outputBitmap: ((Bitmap) -> Unit)? = null) {
        interpreter ?: return
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) return

        val inferenceTime = SystemClock.uptimeMillis()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val tensorImage = TensorImage(INPUT_IMAGE_TYPE).apply {
            load(resizedBitmap)
        }
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter?.run(imageBuffer, output.buffer)

        val bestBoxes = bestBox(output.floatArray, frame)
        val detectionTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect(frame)
            outputBitmap?.invoke(frame)
            return
        }

        // Create processed bitmap with detections if labels are available
        val processedBitmap = if (!labels.isEmpty()) {
            drawDetections(frame, bestBoxes)
        } else {
            null
        }

        // Notify listener
        if (detectorListener is DetectorListener.WithPreview) {
            processedBitmap?.let {
                detectorListener.onProcessedImageReady(it)
                outputBitmap?.invoke(it)
            }
        }
        detectorListener.onDetect(bestBoxes, detectionTime)
    }

    private fun drawDetections(bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)

        boxes.forEach { box ->
            // Draw bounding box
            paint.style = Paint.Style.STROKE
            paint.color = Color.RED
            canvas.drawRect(
                box.x1 * bitmap.width,
                box.y1 * bitmap.height,
                box.x2 * bitmap.width,
                box.y2 * bitmap.height,
                paint
            )

            // Draw label if available
            if (box.classIndex >= 0 && box.classIndex < labels.size) {
                val label = "${labels[box.classIndex]} ${(box.confidence * 100).toInt()}%"
                val textX = box.x1 * bitmap.width
                val textY = (box.y1 * bitmap.height) - paint.textSize/2

                // Draw text background
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(128, 0, 0, 0)
                val textBounds = Rect()
                paint.getTextBounds(label, 0, label.length, textBounds)
                canvas.drawRect(
                    textX,
                    textY - textBounds.height(),
                    textX + textBounds.width(),
                    textY + textBounds.height()/2,
                    paint
                )

                // Draw text
                paint.color = Color.WHITE
                canvas.drawText(label, textX, textY, paint)
            }
        }

        return outputBitmap
    }

    private fun calculateIOU(box1: BoundingBox, box2: BoundingBox): Float {
        val intersectionX1 = max(box1.x1, box2.x1)
        val intersectionY1 = max(box1.y1, box2.y1)
        val intersectionX2 = min(box1.x2, box2.x2)
        val intersectionY2 = min(box1.y2, box2.y2)

        if (intersectionX2 <= intersectionX1 || intersectionY2 <= intersectionY1) {
            return 0f
        }

        val intersectionArea = (intersectionX2 - intersectionX1) * (intersectionY2 - intersectionY1)
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        // Sort boxes by confidence score in descending order
        val sortedBoxes = boxes.sortedByDescending { it.confidence }
        val selected = mutableListOf<BoundingBox>()
        val active = BooleanArray(sortedBoxes.size) { true }

        for (i in sortedBoxes.indices) {
            if (!active[i]) continue

            selected.add(sortedBoxes[i])

            // Compare the selected box with all remaining boxes
            for (j in i + 1 until sortedBoxes.size) {
                if (!active[j]) continue

                // Only compare boxes of the same class
                if (sortedBoxes[i].classIndex == sortedBoxes[j].classIndex) {
                    val iou = calculateIOU(sortedBoxes[i], sortedBoxes[j])
                    if (iou > iouThreshold) {
                        active[j] = false
                    }
                }
            }
        }

        return selected
    }

    private fun bestBox(array: FloatArray, frame: Bitmap): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        // First pass: collect all boxes above confidence threshold
        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxClassIndex = -1
            var j = 4
            var arrayIdx = c + numElements * j

            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxClassIndex = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)

                if (x1 < 0F || x1 > 1F || y1 < 0F || y1 > 1F ||
                    x2 < 0F || x2 > 1F || y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        confidence = maxConf,
                        classIndex = maxClassIndex
                    )
                )
            }
        }

        // Apply Non-Maximum Suppression with current IOU threshold
        return applyNMS(boundingBoxes)
    }

    fun clear() {
        interpreter?.close()
        interpreter = null
    }

    interface DetectorListener {
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
        fun onEmptyDetect(emptyBitmap: Bitmap? = null)

        // Extended interface for preview support
        interface WithPreview : DetectorListener {
            fun onProcessedImageReady(processedBitmap: Bitmap)
            fun getLastProcessedFrame(): Bitmap? = null
            fun onProgressEncode(progress: Int, info: String) {}
        }
    }

    data class BoundingBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val confidence: Float,
        val classIndex: Int = -1
    )
}