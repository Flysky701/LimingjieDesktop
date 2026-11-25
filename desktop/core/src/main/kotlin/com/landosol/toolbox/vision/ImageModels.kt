package com.landosol.toolbox.vision

data class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun crop(x: Int, y: Int, cropWidth: Int, cropHeight: Int): PixelImage {
        require(x >= 0 && y >= 0 && cropWidth > 0 && cropHeight > 0)
        require(x + cropWidth <= width && y + cropHeight <= height)
        val result = IntArray(cropWidth * cropHeight)
        repeat(cropHeight) { row ->
            pixels.copyInto(result, row * cropWidth, (y + row) * width + x, (y + row) * width + x + cropWidth)
        }
        return PixelImage(cropWidth, cropHeight, result)
    }
}

data class DigitTemplates(val digits: Map<Int, PixelImage>) {
    init {
        require((0..9).all(digits::containsKey))
    }
}

data class BinaryBounds(val left: Int, val top: Int, val rightExclusive: Int, val bottomExclusive: Int) {
    init {
        require(left >= 0 && top >= 0)
        require(rightExclusive > left && bottomExclusive > top)
    }

    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top
}

data class BinaryImage(val width: Int, val height: Int, val pixels: BooleanArray) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Boolean = pixels[y * width + x]

    fun foregroundBounds(): BinaryBounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        repeat(height) { y ->
            repeat(width) { x ->
                if (this[x, y]) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else BinaryBounds(left, top, right + 1, bottom + 1)
    }
}

object StructuralMatcher {
    fun iou(first: BinaryImage, second: BinaryImage): Double {
        require(first.width == second.width && first.height == second.height)
        var intersection = 0
        var union = 0
        first.pixels.indices.forEach { index ->
            if (first.pixels[index] && second.pixels[index]) intersection++
            if (first.pixels[index] || second.pixels[index]) union++
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}

