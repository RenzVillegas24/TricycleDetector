package com.example.TricycleDetector.Detector

import android.content.Context
import android.net.Uri

class ImageDetector(
    context: Context,
    modelPath: String,
    labelPath: String?,
    detectorListener: DetectorListener
) : Detector(context, modelPath, labelPath, detectorListener) {

    fun detectFromUri(context: Context, imageUri: Uri) {
        try {
            // Load bitmap from Uri
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            detect(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detectFromPath(path: String) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
            detect(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
