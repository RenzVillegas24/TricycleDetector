package com.example.TricycleDetector

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.graphics.Bitmap
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import com.example.TricycleDetector.Constants.LABEL_PATH
import com.example.TricycleDetector.Constants.MODEL_PATH
import com.example.TricycleDetector.Detector.Detector
import com.example.TricycleDetector.Detector.ImageDetector
import com.example.TricycleDetector.databinding.FragmentImageDetectorBinding

class ImageDetectorFragment : Fragment(R.layout.fragment_image_detector) {
    private lateinit var binding: FragmentImageDetectorBinding
    private lateinit var imageDetector: ImageDetector
    private var isProcessing = false
    private var currentImageUri: Uri? = null

    private val selectImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            currentImageUri = uri
            binding.imageView.setImageURI(uri)
            binding.processImageButton.isEnabled = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentImageDetectorBinding.bind(view)

        setupDetector()
        setupUI()
    }

    private fun setupDetector() {
        imageDetector = ImageDetector(
            requireContext(),
            MODEL_PATH,
            LABEL_PATH,
            object : Detector.DetectorListener.WithPreview {
                override fun onDetect(boundingBoxes: List<Detector.BoundingBox>, inferenceTime: Long) {
                    isProcessing = false
                    activity?.runOnUiThread {
                        updateUIState()
                    }
                }

                override fun onEmptyDetect(emptyBitmap: Bitmap?) {
                    isProcessing = false
                    activity?.runOnUiThread {
                        updateUIState()
                        showMessage("No objects detected")
                    }
                }

                override fun onProcessedImageReady(processedBitmap: Bitmap) {
                    activity?.runOnUiThread {
                        binding.imageView.setImageBitmap(processedBitmap)
                    }
                }
            }
        )
    }

    private fun setupUI() {
        binding.apply {
            // Initially disable process button until image is selected
            processImageButton.isEnabled = false

            selectImageButton.setOnClickListener {
                selectImage.launch("image/*")
            }

            processImageButton.setOnClickListener {
                currentImageUri?.let { uri ->
                    isProcessing = true
                    updateUIState()
                    imageDetector.detectFromUri(requireContext(), uri)
                }
            }
        }
    }

    private fun updateUIState() {
        binding.apply {
            progressBar.isVisible = isProcessing
            selectImageButton.isEnabled = !isProcessing
            processImageButton.apply {
                isVisible = !isProcessing
                isEnabled = !isProcessing && currentImageUri != null
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}