package com.airportweather.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

fun createDotBitmap(
    size: Int,
    fillColor: Int,
    borderColor: Int,
    borderWidth: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.isAntiAlias = true

    paint.color = Color.BLACK
    if (borderColor == Color.WHITE || borderColor == fillColor) {
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth + 1, paint)
    } else {
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    }

    if (borderColor != Color.WHITE && borderColor != fillColor) {
        paint.color = borderColor
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 1, paint)
    }

    paint.color = fillColor
    canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth, paint)

    return bitmap
}

fun createTextBitmap(
    text: String,
    textColor: Int,
    bgColor: Int = Color.TRANSPARENT,
    size: Float = 40F
): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)

    val padding = 10
    val width = bounds.width() + 2 * padding
    val height = bounds.height() + 2 * padding

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    if (bgColor != Color.TRANSPARENT) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    val textHeight = bounds.height()
    val adjustedY = height / 2f + textHeight / 2f - paint.descent() / 3

    canvas.drawText(text, padding.toFloat(), adjustedY, paint)

    return bitmap
}

fun createWindBarbBitmap(windSpeedKt: Int, windDirDegrees: Int?): Bitmap? {
    if (windSpeedKt < 4) return null

    val isVariable = windDirDegrees == null

    val size = 150
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    return when {
        isVariable -> {
            canvas.drawCircle(size / 2f, size / 2f, 40f, paint)

            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                canvas.save()
                canvas.rotate(angle, size / 2f, size / 2f)
                canvas.drawLine(size / 2f, 30f, size / 2f, 50f, paint)
                canvas.restore()
            }

            paint.style = Paint.Style.FILL
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${windSpeedKt}kt", size / 2f, size - 20f, paint)
            bitmap
        }

        else -> {
            val centerX = size / 2f
            val centerY = size / 2f
            val staffLength = size * 0.4f

            // Wind comes FROM windDirDegrees, so rotate the canvas to that bearing
            canvas.save()
            if (windDirDegrees != null) {
                canvas.rotate(windDirDegrees.toFloat(), centerX, centerY)
            }

            canvas.drawLine(
                centerX, centerY,
                centerX, centerY - staffLength,
                paint
            )

            var remainingKts = windSpeedKt
            val flags = remainingKts / 50
            remainingKts %= 50
            val fullLines = remainingKts / 10
            remainingKts %= 10
            val halfLines = remainingKts / 5

            var currentY = centerY - staffLength

            repeat(flags) {
                canvas.drawLine(
                    centerX, currentY,
                    centerX + 30f, currentY - 30f,
                    paint
                )
                currentY += 30f
            }

            repeat(fullLines) {
                canvas.drawLine(
                    centerX, currentY,
                    centerX + 30f, currentY,
                    paint
                )
                currentY += 20f
            }

            repeat(halfLines) {
                canvas.drawLine(
                    centerX, currentY,
                    centerX + 15f, currentY,
                    paint
                )
                currentY += 20f
            }

            canvas.restore()
            bitmap
        }
    }
}

fun flightCategoryColor(category: String?): Int = when (category?.uppercase()) {
    "VFR" -> Color.GREEN
    "MVFR" -> Color.parseColor("#0080FF")
    "IFR" -> Color.RED
    "LIFR" -> Color.parseColor("#FF00FF")
    else -> Color.WHITE
}
