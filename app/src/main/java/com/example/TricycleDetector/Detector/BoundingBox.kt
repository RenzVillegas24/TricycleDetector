package com.example.TricycleDetector.Detector


import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.TricycleDetector.Detector.Detector.BoundingBox

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<BoundingBox> = emptyList()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun updateDetections(newDetections: List<BoundingBox>) {
        detections = newDetections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        detections.forEach { box ->
            canvas.drawRect(
                box.x1 * width,
                box.y1 * height,
                box.x2 * width,
                box.y2 * height,
                paint
            )
        }
    }
}