// VideoDetectorFragment.kt
package com.example.TricycleDetector

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.view.View
import androidx.core.view.isVisible
import com.example.TricycleDetector.Detector.Detector
import com.example.TricycleDetector.Detector.VideoDetector
import com.example.TricycleDetector.databinding.FragmentVideoDetectorBinding
import android.widget.FrameLayout
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.widget.ImageView
import android.view.ViewGroup.LayoutParams
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.example.TricycleDetector.Constants.LABEL_PATH
import com.example.TricycleDetector.Constants.MODEL_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class VideoDetectorFragment : Fragment(R.layout.fragment_video_detector) {
    private lateinit var binding: FragmentVideoDetectorBinding
    private lateinit var videoDetector: VideoDetector
    private var selectedVideoUri: Uri? = null
    private var processedVideoUri: Uri? = null
    private lateinit var previewImageView: ImageView
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val availableFps = listOf(10.0, 15.0, 24.0, 30.0)
    private var targetFps = 10.0
    private var sourceFrameRate = 30.0
    private var frameInterval = 3
    private var videoDetails = mutableMapOf<String, String>()

    private val selectVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedVideoUri = it
            processedVideoUri = null
            setupVideoView(it)
            setupPreviewOverlay()
            binding.videoView.start()
            binding.videoInfoCard.isVisible = true
            binding.videoView.isVisible = true
            updateControlsVisibility(true)
            getVideoInfo(it)

        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentVideoDetectorBinding.bind(view)

        setupDetector()
        setupUI()
        setupFpsSpinner()
        binding.videoView.background = null
    }

    private fun setupFpsSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.fps_spinner_item,
            availableFps.map { "${it.toInt()} fps" }
        )
        adapter.setDropDownViewResource(R.layout.fps_spinner_dropdown_item)

        binding.fpsSpinner.adapter = adapter
        binding.fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                targetFps = availableFps[position]
                frameInterval = (sourceFrameRate / targetFps).roundToInt()
//                if (selectedVideoUri != null) {
//                    startProcessing()
//                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

    private fun getVideoInfo(uri: Uri) {
        val retriever = videoDetector.getMediaInfoExtractor()


        val inputPath = context?.let { getRealPathFromUri(it, uri) }
        if (inputPath != null) {
            binding.processVideoButton.isEnabled = true
            binding.playPauseButton.isEnabled = true
            retriever.getMediaInfo(inputPath) { mediaInfo ->
                mediaInfo?.let {

                    // Get duration in milliseconds
                    val durationMs = it.duration.toLong() * 1000
                    val duration = if (durationMs < 60000) {
                        "${(durationMs / 1000)}s"
                    } else {
                        "${(durationMs / 60000)}:${((durationMs % 60000) / 1000).toString().padStart(2, '0')}"
                    }

                    videoDetails = mutableMapOf(
                        "resolution" to "${it.videoStreams.firstOrNull()?.width}x${it.videoStreams.firstOrNull()?.height}",
                        "duration" to duration,
                        "originalFps" to String.format("%.2f", it.videoStreams.firstOrNull()?.frameRate)
                    )

                    updateVideoInfo()
                }
            }
        }


    }

    private fun updateVideoInfo() {
        binding.apply {
            resolutionText.text = videoDetails["resolution"]
            originalFpsText.text = "${videoDetails["originalFps"]} fps"
            durationText.text = videoDetails["duration"]
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        processingExecutor.shutdown()
    }

    private fun setupPreviewOverlay() {
        previewImageView = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }
        binding.videoContainer.addView(previewImageView)
        previewImageView.isVisible = false
    }

    private fun setupVideoView(uri: Uri) {
        // Calculate the aspect ratio constraints
        val aspectRatio = videoDetector.getVideoAspectRatio()

        binding.videoView.apply {

            setOnPreparedListener { mediaPlayer ->
                // Update initial play state
                updatePlayPauseIcon(true)

                // Set up completion listener
                mediaPlayer.setOnCompletionListener {
                    updatePlayPauseIcon(false)
                }
            }

            // Monitor playback state changes
            setOnInfoListener { _, what, _ ->
                when (what) {
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        binding.playPauseButton.isEnabled = false
                    }
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        binding.playPauseButton.isEnabled = true
                        updatePlayPauseIcon(isPlaying)
                    }
                }
                true
            }
        }

        binding.videoContainer.post {
            val containerWidth = binding.videoContainer.width
            val containerHeight = binding.videoContainer.height

            val videoWidth: Int
            val videoHeight: Int

            if (containerWidth / aspectRatio <= containerHeight) {
                // Width constrained
                videoWidth = containerWidth
                videoHeight = (containerWidth / aspectRatio).toInt()
            } else {
                // Height constrained
                videoHeight = containerHeight
                videoWidth = (containerHeight * aspectRatio).toInt()
            }

            binding.videoView.layoutParams = FrameLayout.LayoutParams(videoWidth, videoHeight).apply {
                gravity = android.view.Gravity.CENTER
            }

            previewImageView.layoutParams = FrameLayout.LayoutParams(videoWidth, videoHeight).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        binding.videoView.setVideoURI(uri)
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.playPauseButton.setIconResource(
            if (isPlaying) R.drawable.ic_pause_rounded
            else R.drawable.ic_play_arrow_rounded
        )
    }


    private fun setupDetector() {
        videoDetector = VideoDetector(
            requireContext(),
            MODEL_PATH,
            LABEL_PATH,
            object : Detector.DetectorListener.WithPreview {
                override fun onProcessedImageReady(processedBitmap: Bitmap) {
                    activity?.runOnUiThread {
                        previewImageView.setImageBitmap(processedBitmap)
                        previewImageView.isVisible = true
                        binding.videoView.pause()
                    }
                }

                override fun onProgressEncode(progress: Int, info: String) {
                    activity?.runOnUiThread {
                        binding.resultText.text = info
                    }
                }

                override fun onDetect(boundingBoxes: List<Detector.BoundingBox>, inferenceTime: Long) {
                    val result = buildString {
                        append("Detection time: ${inferenceTime}ms\n")
                        append("Detected: ${boundingBoxes.size}")
                    }
                    activity?.runOnUiThread {
                        binding.resultText.text = result
                    }
                }

                override fun onEmptyDetect(emptyBitmap: Bitmap?) {
                    activity?.runOnUiThread {
                        binding.resultText.text = "No objects detected"
                    }
                }
            }
        )
    }

    private fun startProcessing() {
        binding.apply {
            progressIndicator.isVisible = true
            processVideoButton.isEnabled = false
            resultText.isVisible = true
            controlsLayout.isVisible = false
            playPauseButton.isEnabled = false
            videoView.isVisible = true
        }

        // Launch a coroutine in the lifecycle scope
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                selectedVideoUri?.let { uri ->
                    val processedVideoFile = videoDetector.detectFromUri(
                        requireContext(),
                        uri,
                        targetFps,
                        onProgress = { progress ->
                            // Wrap UI updates in withContext(Dispatchers.Main)
                            activity?.runOnUiThread {
                                binding.progressIndicator.setProgress((progress * 100).toInt(), true)
                            }
                        },
                        onInfo = { info ->
                            // Wrap UI updates in withContext(Dispatchers.Main)
                            activity?.runOnUiThread {
                                binding.resultText.text = info
                            }
                        }
                    )

                    // Switch to main thread for UI updates
                    withContext(Dispatchers.Main) {
                        binding.apply {
                            progressIndicator.isVisible = false
                            progressIndicator.setProgress(0)
                            resultText.isVisible = false
                            controlsLayout.isVisible = true
                            Log.d("VideoDetectorFragment", "Processing finished")
                        }

                        processedVideoFile?.let { file ->
                            previewImageView.isVisible = false
                            processedVideoUri = Uri.fromFile(file)
                            Log.d("VideoDetectorFragment", "Processed video saved to: ${processedVideoUri}")
                            binding.playPauseButton.isEnabled = true
                            setupVideoView(processedVideoUri!!)
                            binding.videoView.start()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoDetectorFragment", "Error processing video", e)
                withContext(Dispatchers.Main) {
                    binding.apply {
                        progressIndicator.isVisible = false
                        progressIndicator.setProgress(0)
                        resultText.isVisible = false
                        controlsLayout.isVisible = true
                    }
                }
            }
        }
    }

    private fun updateControlsVisibility(visible: Boolean) {
        binding.apply {
            controlsCard.isVisible = visible
            if (visible) {
                playPauseButton.setIconResource(
                    if (videoView.isPlaying) R.drawable.ic_pause_rounded
                    else R.drawable.ic_play_arrow_rounded
                )
            }
        }
    }

    private fun setupUI() {
        binding.apply {
            selectVideoButton.setOnClickListener {
                selectVideo.launch("video/*")
            }

            processVideoButton.setOnClickListener {
                startProcessing()
            }

            playPauseButton.setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    playPauseButton.setIconResource(R.drawable.ic_play_arrow_rounded)
                } else {
                    videoView.start()
                    playPauseButton.setIconResource(R.drawable.ic_pause_rounded)
                }
            }
        }
    }
}