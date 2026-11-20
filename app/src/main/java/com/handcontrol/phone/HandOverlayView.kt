package com.handcontrol.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.min

/** Draws the hand skeleton over the (letter-boxed) camera preview. */
class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EEAD4.toInt()
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var points: FloatArray? = null
    private var imgW = 0
    private var imgH = 0

    private val connections = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    fun setResult(landmarks: List<NormalizedLandmark>?, width: Int, height: Int) {

        imgW = width
        imgH = height

        points = landmarks?.let { list ->
            val out = FloatArray(list.size * 2)
            for (i in list.indices) {
                out[2 * i] = list[i].x()
                out[2 * i + 1] = list[i].y()
            }
            out
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val p = points ?: return

        if (p.size < 42 || imgW <= 0 || imgH <= 0) return

        val scale = min(width / imgW.toFloat(), height / imgH.toFloat())
        val ox = (width - imgW * scale) / 2f
        val oy = (height - imgH * scale) / 2f

        fun px(i: Int) = ox + p[2 * i] * imgW * scale
        fun py(i: Int) = oy + p[2 * i + 1] * imgH * scale

        for ((a, b) in connections) {
            canvas.drawLine(px(a), py(a), px(b), py(b), linePaint)
        }

        for (i in 0 until 21) {
            canvas.drawCircle(px(i), py(i), 7f, pointPaint)
        }
    }
}
