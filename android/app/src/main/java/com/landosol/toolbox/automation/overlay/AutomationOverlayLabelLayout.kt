package com.landosol.toolbox.automation.overlay

/**
 * Wraps a debug label using the same measured width that the annotation canvas will use.
 * Chinese labels cannot rely on whitespace as a break point, so wrapping is code-point based.
 */
internal fun wrapAutomationOverlayLabel(
    label: String,
    maxLineWidth: Float,
    maxLines: Int,
    measureText: (String) -> Float,
): List<String> {
    require(maxLines > 0)
    if (label.isEmpty() || maxLineWidth <= 0f) return emptyList()

    val lines = mutableListOf<String>()
    var current = StringBuilder()

    fun flushCurrent(forceEmptyLine: Boolean = false) {
        if (current.isNotEmpty() || forceEmptyLine) {
            lines += current.toString()
            current = StringBuilder()
        }
    }

    var offset = 0
    while (offset < label.length) {
        val codePoint = label.codePointAt(offset)
        offset += Character.charCount(codePoint)
        if (codePoint == '\n'.code) {
            flushCurrent(forceEmptyLine = true)
            continue
        }
        val unit = String(Character.toChars(codePoint))
        val candidate = current.toString() + unit
        if (current.isNotEmpty() && measureText(candidate) > maxLineWidth) {
            flushCurrent()
            current.append(unit)
        } else {
            current.append(unit)
        }
    }
    flushCurrent()
    if (lines.size <= maxLines) return lines

    val visible = lines.take(maxLines).toMutableList()
    var last = visible.last().trimEnd()
    while (last.isNotEmpty() && measureText(last + LABEL_ELLIPSIS) > maxLineWidth) {
        val previousOffset = last.offsetByCodePoints(last.length, -1)
        last = last.substring(0, previousOffset).trimEnd()
    }
    visible[visible.lastIndex] = last + LABEL_ELLIPSIS
    return visible
}

private const val LABEL_ELLIPSIS = "…"

