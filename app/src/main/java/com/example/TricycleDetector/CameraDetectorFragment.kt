package com.example.TricycleDetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.TricycleDetector.Constants.LABEL_PATH
import com.example.TricycleDetector.Constants.MODEL_PATH
import com.example.TricycleDetector.Detector.CameraDetector
import com.example.TricycleDetector.Detector.Detector
import com.example.TricycleDetector.databinding.FragmentCameraDetectorBinding

class CameraDetectorFragment : Fragment(R.layout.fragment_camera_detector) {
    private lateinit var binding: FragmentCameraDetectorBinding
    private lateinit var cameraDetector: CameraDetector
    private var isCameraActive = false

    // Register the permissions callback
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(
                context,
                "Camera permission is required for detection",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentCameraDetectorBinding.bind(view)

        setupDetector()
        setupUI()
    }

    private fun setupDetector() {
        cameraDetector = CameraDetector(
            requireContext(),
            MODEL_PATH,
            LABEL_PATH,
            object : Detector.DetectorListener {
                override fun onDetect(boundingBoxes: List<Detector.BoundingBox>, inferenceTime: Long) {
                    val result = buildString {
                        append("Detection time: ${inferenceTime}ms\n")
                        boundingBoxes.forEachIndexed { index, box ->
                            append("Detection $index:\n")
                            append("  Class: ${getClassName(box.classIndex)}\n")
                            append("  Confidence: ${String.format("%.2f", box.confidence * 100)}%\n")
                            append("  Location: (${String.format("%.2f", box.cx)}, " +
                                    "${String.format("%.2f", box.cy)})\n")
                        }
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
            },
            requireActivity()
        )
    }

    private fun setupUI() {
        binding.toggleCameraButton.setOnClickListener {
            toggleCamera()
        }

        // Setup overlay for drawing bounding boxes
        val overlay = DetectionOverlay(requireContext())
        binding.previewView.overlay.add(overlay)
    }

    private fun toggleCamera() {
        if (isCameraActive) {
            stopCamera()
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        if (checkCameraPermission()) {
            cameraDetector.startCamera(
                binding.previewView,
                Size(binding.previewView.width, binding.previewView.height)
            )
            isCameraActive = true
            binding.toggleCameraButton.text = "Stop Camera"
        } else {
            requestCameraPermission()
        }
    }

    private fun stopCamera() {
        cameraDetector.stopCamera()
        isCameraActive = false
        binding.toggleCameraButton.text = "Start Camera"
        binding.resultText.text = ""
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun getClassName(classIndex: Int): String {
        // Replace this with your actual class names from best.txt
        return "Class $classIndex"
    }

    override fun onPause() {
        super.onPause()
        if (isCameraActive) {
            stopCamera()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isCameraActive) {
            stopCamera()
        }
    }
}

// Custom view for drawing detection overlays
class DetectionOverlay(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = 32f
    }

    private var detections: List<Detector.BoundingBox> = emptyList()
    private var viewWidth = 0
    private var viewHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    fun updateDetections(boundingBoxes: List<Detector.BoundingBox>) {
        detections = boundingBoxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        detections.forEach { box ->
            // Convert normalized coordinates to view coordinates
            val left = box.x1 * viewWidth
            val top = box.y1 * viewHeight
            val right = box.x2 * viewWidth
            val bottom = box.y2 * viewHeight

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, paint)

            // Draw confidence score
            val label = String.format("%.1f%%", box.confidence * 100)
            canvas.drawText(label, left, top - 10f, paint)
        }
    }
}