package com.example.TricycleDetector.Detector

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.floor

class VideoDetector(
    context: Context,
    modelPath: String,
    labelPath: String?,
    detectorListener: DetectorListener
) : Detector(context, modelPath, labelPath, detectorListener) {

    private var isProcessing = false
    private var processedFrames = mutableListOf<Bitmap>()
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0
    private var currentFrameRate = 30.0
    private var currentDuration = 0.0
    private val mediaInfoExtractor = MediaInfoExtractor()

    fun getMediaInfoExtractor(): MediaInfoExtractor {
        return mediaInfoExtractor
    }


    init {
        // Enable FFmpeg debug logs for hardware acceleration
        FFmpegKitConfig.enableLogCallback { log ->
            Log.d("FFmpegKit", log.message)
        }
    }

    private suspend fun detectAndProcess(frame: Bitmap): Bitmap = suspendCoroutine { continuation ->
        detect(frame) { result ->
            continuation.resume(result)
        }
    }

    private suspend fun checkHardwareAccelerationSupport(): Boolean = withContext(Dispatchers.IO) {
        try {
            val testCmd = "-hide_banner -encoders"
            val session = FFmpegKit.execute(testCmd)
            val output = session.output

            // Check if hardware encoders are available
            return@withContext output.contains("h264_mediacodec") ||
                    output.contains("hevc_mediacodec") ||
                    output.contains("mpeg4_mediacodec")
        } catch (e: Exception) {
            Log.e("VideoDetector", "Error checking hardware acceleration", e)
            return@withContext false
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun detectFromUri(
        context: Context,
        videoUri: Uri,
        frameInterval: Double = 30.0,
        onProgress: ((Float) -> Unit)? = null,
        onInfo: ((String) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        if (isProcessing) {
            return@withContext null
        }

        var outputFile: File? = null
        try {
            isProcessing = true
            processedFrames.clear()

            // Create temporary directory for frames
            val framesDir = File(context.cacheDir, "video_frames_${System.currentTimeMillis()}")
            framesDir.mkdirs()

            // Extract video info
            val inputPath = getRealPathFromUri(context, videoUri)
            mediaInfoExtractor.getMediaInfo(inputPath) { mediaInfo ->
                currentVideoWidth = (mediaInfo?.videoStreams?.firstOrNull()?.width ?: 0).toInt()
                currentVideoHeight = (mediaInfo?.videoStreams?.firstOrNull()?.height ?: 0).toInt()
                currentFrameRate = mediaInfo?.videoStreams?.firstOrNull()?.frameRate ?: 30.0
                currentDuration = mediaInfo?.duration ?: 0.0
                Log.d("INFO", "Width: $currentVideoWidth, Height: $currentVideoHeight, FrameRate: $currentFrameRate, Duration: $currentDuration")
            }

            val currentFrames = currentDuration * currentFrameRate
            val length = if (currentFrames == 0.0) 1 else Math.log10(Math.abs(currentFrames)).toInt() + 1
            var computedFrames = floor(currentDuration * frameInterval).toInt()

            // Check hardware acceleration support
            val hasHardwareAcceleration = checkHardwareAccelerationSupport()
            Log.d("VideoDetector", "Hardware acceleration available: $hasHardwareAcceleration")

            // Prepare hardware-accelerated extraction command
            val extractCmd = buildString {
                append("-hwaccel mediacodec ") // Enable hardware decoding
                append("-i $inputPath ")
                append("-vf fps=${frameInterval} ") // Frame extraction
                append("-vsync 0 ") // Disable video sync to improve performance
                append("-frame_drop_threshold 1 ") // Allow frame dropping for performance
                append("${framesDir.absolutePath}/frame_%0${length}d.jpg")
            }

            // Keep track of processed frames
            val processedFrameIndices = mutableSetOf<Int>()

            // Start monitoring job
            val monitorJob = launch(Dispatchers.IO) {
                while (isProcessing && (processedFrameIndices.size != computedFrames)) {
                    val frameFiles = framesDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                    if (frameFiles.isEmpty() || frameFiles.size <= processedFrameIndices.size) {
//                        delay(75)
                        continue
                    }

                    val frameFile = frameFiles[processedFrameIndices.size]
                    if (frameFile.exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(frameFile.absolutePath)
                        bitmap?.let {
                            val processedFrame = detectAndProcess(it)
                            Log.d("VideoDetector", "Processed frame ${processedFrameIndices.size}")
                            processedFrames.add(processedFrame)
                            processedFrameIndices.add(processedFrameIndices.size)

                            withContext(Dispatchers.Main) {
                                if (detectorListener is DetectorListener.WithPreview) {
                                    detectorListener.onProcessedImageReady(processedFrame)
                                }
                                onProgress?.invoke(processedFrameIndices.size.toFloat() / computedFrames)
                            }
                        }
                    }
                }
            }

            // Execute FFmpeg extraction
            val extractSession = FFmpegKit.execute(extractCmd)

            if (extractSession.returnCode.value == ReturnCode.SUCCESS) {
                computedFrames = framesDir.listFiles()?.size ?: computedFrames
                monitorJob.join()

                if (processedFrames.isNotEmpty()) {
                    outputFile = saveProcessedVideoWithHardwareAcceleration(
                        context,
                        processedFrames,
                        frameInterval,
                        hasHardwareAcceleration,
                        onProgress = onProgress,
                        onInfo = onInfo)
                }
            } else {
                Log.e("VideoDetector", "FFmpeg extraction failed: ${extractSession.output}")
            }

            // Cleanup
            monitorJob.cancel()
            framesDir.deleteRecursively()
            processedFrames.clear()

        } catch (e: Exception) {
            Log.e("VideoDetector", "Error processing video", e)
        } finally {
            isProcessing = false
        }

        outputFile
    }


    private suspend fun saveProcessedVideoWithHardwareAcceleration(
        context: Context,
        frames: List<Bitmap>,
        frameInterval: Double,
        useHardwareAcceleration: Boolean,
        onProgress: ((Float) -> Unit)? = null,
        onInfo: ((String) -> Unit)? = null
    ): File {
        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "processed_video_${System.currentTimeMillis()}.mp4"
        )

        val processedFramesDir = File(context.cacheDir, "processed_frames_${System.currentTimeMillis()}")
        processedFramesDir.mkdirs()

        try {
            // Optimize frame saving using parallel processing
            coroutineScope {
                frames.mapIndexed { index, frame ->
                    async(Dispatchers.Default) {
                        val frameFile = File(processedFramesDir, "frame_$index.jpg")
                        FileOutputStream(frameFile).use { out ->
                            // Use ARGB_8888 for best quality and compatibility
                            onProgress?.invoke((index + 1).toFloat() / frames.size)
                            onInfo?.invoke("Saving frame $index")

                            val optimizedBitmap = if (frame.config != Bitmap.Config.ARGB_8888) {
                                frame.copy(Bitmap.Config.ARGB_8888, false)
                            } else {
                                frame
                            }

                            optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)

                            // Only recycle the copy if we created one
                            if (optimizedBitmap !== frame) {
                                optimizedBitmap.recycle()
                            }

                        }

                    }
                }.awaitAll()
            }

            // Combine frames into video using FFmpeg
            val ffmpegCmd = "-hwaccel mediacodec -framerate $frameInterval -i ${processedFramesDir.absolutePath}/frame_%d.jpg " +
                    "-c:v mpeg4 -q:v 5 " +
                    outputFile.absolutePath

            FFmpegKitConfig.enableLogCallback { log ->
                val progress = parseProgress(log.message)
                if (progress != null) {
                    onProgress?.invoke(progress)
                    onInfo?.invoke("Encoding video: ${"%.2f".format(progress * 100)}%")
                }
            }

            val session = FFmpegKit.execute(ffmpegCmd)


            if (session.returnCode.value != ReturnCode.SUCCESS) {
                throw Exception("FFmpeg encoding failed: ${session.output}")
            }

        } catch (e: Exception) {
            Log.e("VideoDetector", "Error saving video", e)
            if (useHardwareAcceleration) {
                Log.w("VideoDetector", "Hardware encoding failed with exception, falling back to software encoding")
                return saveProcessedVideoWithHardwareAcceleration(context, frames, frameInterval, false)
            }
            throw e
        } finally {
            processedFramesDir.deleteRecursively()
        }

        return outputFile
    }
    private fun getRealPathFromUri(context: Context, uri: Uri): String {
        // Implement URI to file path conversion based on your needs
        // This is a simplified version
        return when (uri.scheme) {
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val tempFile = File.createTempFile("video", ".mp4", context.cacheDir)
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    tempFile.absolutePath
                } ?: throw Exception("Cannot access content: $uri")
            }
            "file" -> uri.path ?: throw Exception("Invalid file URI: $uri")
            else -> throw Exception("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    private fun parseProgress(logMessage: String): Float? {
        return try {
            // FFmpeg typically outputs progress in format "frame= 120 fps= 23 q=28.0 size=    384kB time=00:00:04.00 bitrate= 785.5kbits/s"
            if (!logMessage.contains("time=")) {
                return null
            }

            // Extract the timestamp in format HH:mm:ss.SS
            val timePattern = """time=\s*(\d{2}:\d{2}:\d{2}\.\d{2})""".toRegex()
            val matchResult = timePattern.find(logMessage) ?: return null
            val timeStr = matchResult.groupValues[1]

            // Parse the timestamp components
            val parts = timeStr.split(":", ".")
            if (parts.size != 4) return null

            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            val seconds = parts[2].toInt()
            val centiseconds = parts[3].toInt()

            // Convert to total seconds
            val currentTime = hours * 3600 + minutes * 60 + seconds + centiseconds / 100.0

            // Calculate progress percentage (currentTime / totalDuration)
            // Using the class property currentDuration
            if (currentDuration <= 0) return null

            (currentTime / currentDuration).toFloat().coerceIn(0f, 1f)
        } catch (e: Exception) {
            null
        }
    }

    fun getVideoAspectRatio(): Float {
        return if (currentVideoHeight > 0) {
            currentVideoWidth.toFloat() / currentVideoHeight.toFloat()
        } else {
            16f/9f
        }
    }

    fun stopProcessing() {
        isProcessing = false
        FFmpegKit.cancel()
    }
}