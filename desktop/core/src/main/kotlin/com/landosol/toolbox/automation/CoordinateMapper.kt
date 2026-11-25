package com.landosol.toolbox.automation

data class ScreenPoint(val x: Float, val y: Float)

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "尺寸必须大于 0" }
    }
}

data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right > left && bottom > top) { "矩形范围无效" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(point: ScreenPoint): Boolean = point.x in left..right && point.y in top..bottom
}

data class CoordinateCalibration(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class CoordinateMapping(
    val referenceSize: PixelSize,
    val screenBounds: PixelRect,
    val viewport: PixelRect,
    val scale: Float,
    val calibration: CoordinateCalibration,
) {
    fun toScreen(referencePoint: ScreenPoint): ScreenPoint? {
        val referenceBounds = PixelRect(0f, 0f, referenceSize.width.toFloat(), referenceSize.height.toFloat())
        if (!referenceBounds.contains(referencePoint)) return null
        return ScreenPoint(
            x = viewport.left + referencePoint.x * scale + calibration.offsetX,
            y = viewport.top + referencePoint.y * scale + calibration.offsetY,
        ).takeIf(screenBounds::contains)
    }

    fun toReference(screenPoint: ScreenPoint): ScreenPoint? {
        val calibratedViewport = PixelRect(
            viewport.left + calibration.offsetX,
            viewport.top + calibration.offsetY,
            viewport.right + calibration.offsetX,
            viewport.bottom + calibration.offsetY,
        )
        if (!calibratedViewport.contains(screenPoint)) return null
        return ScreenPoint(
            x = (screenPoint.x - calibratedViewport.left) / scale,
            y = (screenPoint.y - calibratedViewport.top) / scale,
        )
    }
}

class CoordinateMapper(
    private val referenceSize: PixelSize = PixelSize(1920, 1080),
) {
    fun createMapping(
        screenBounds: PixelRect,
        calibration: CoordinateCalibration = CoordinateCalibration(),
    ): CoordinateMapping {
        val scale = minOf(
            screenBounds.width / referenceSize.width,
            screenBounds.height / referenceSize.height,
        )
        val viewportWidth = referenceSize.width * scale
        val viewportHeight = referenceSize.height * scale
        val viewport = PixelRect(
            left = screenBounds.left + (screenBounds.width - viewportWidth) / 2f,
            top = screenBounds.top + (screenBounds.height - viewportHeight) / 2f,
            right = screenBounds.left + (screenBounds.width + viewportWidth) / 2f,
            bottom = screenBounds.top + (screenBounds.height + viewportHeight) / 2f,
        )
        return CoordinateMapping(referenceSize, screenBounds, viewport, scale, calibration)
    }
}

