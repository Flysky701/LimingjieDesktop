package com.landosol.toolbox.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object ToolboxIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()

    val Home = icon("Home") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); verticalLineTo(20f); horizontalLineTo(10f); verticalLineTo(14f)
        horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(19f); verticalLineTo(9f)
    }
    val Strategy = icon("Strategy") {
        moveTo(4f, 6f); horizontalLineTo(8f); moveTo(12f, 6f); horizontalLineTo(20f)
        moveTo(8f, 3f); horizontalLineTo(12f); verticalLineTo(9f); horizontalLineTo(8f); close()
        moveTo(4f, 17f); horizontalLineTo(14f); moveTo(18f, 17f); horizontalLineTo(20f)
        moveTo(14f, 14f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(14f); close()
    }
    val Accounts = icon("Accounts") {
        moveTo(16f, 7f); curveTo(16f, 9.2f, 14.2f, 11f, 12f, 11f)
        curveTo(9.8f, 11f, 8f, 9.2f, 8f, 7f); curveTo(8f, 4.8f, 9.8f, 3f, 12f, 3f)
        curveTo(14.2f, 3f, 16f, 4.8f, 16f, 7f); close()
        moveTo(5f, 21f); verticalLineTo(19f); curveTo(5f, 15.5f, 8f, 14f, 12f, 14f)
        curveTo(16f, 14f, 19f, 15.5f, 19f, 19f); verticalLineTo(21f)
    }
    val Settings = icon("Settings") {
        moveTo(9f, 3f); horizontalLineTo(15f); lineTo(16f, 6f); lineTo(19f, 7f)
        lineTo(22f, 12f); lineTo(19f, 17f); lineTo(16f, 18f); lineTo(15f, 21f)
        horizontalLineTo(9f); lineTo(8f, 18f); lineTo(5f, 17f); lineTo(2f, 12f)
        lineTo(5f, 7f); lineTo(8f, 6f); close()
        moveTo(15f, 12f); curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
        curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f); curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
        curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f); close()
    }
    val Back = icon("Back") {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }
    val Sun = icon("Sun") {
        moveTo(16f, 12f); curveTo(16f, 14.2f, 14.2f, 16f, 12f, 16f)
        curveTo(9.8f, 16f, 8f, 14.2f, 8f, 12f); curveTo(8f, 9.8f, 9.8f, 8f, 12f, 8f)
        curveTo(14.2f, 8f, 16f, 9.8f, 16f, 12f); close()
        moveTo(12f, 2f); verticalLineTo(4f); moveTo(12f, 20f); verticalLineTo(22f)
        moveTo(2f, 12f); horizontalLineTo(4f); moveTo(20f, 12f); horizontalLineTo(22f)
        moveTo(5f, 5f); lineTo(6.5f, 6.5f); moveTo(17.5f, 17.5f); lineTo(19f, 19f)
        moveTo(5f, 19f); lineTo(6.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(19f, 5f)
    }
    val Moon = icon("Moon") {
        moveTo(21f, 13f); curveTo(20.4f, 17.6f, 16.5f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7.5f, 6.4f, 3.6f, 11f, 3f)
        curveTo(8f, 7f, 10f, 13f, 15f, 14f); curveTo(17f, 14.4f, 19f, 14f, 21f, 13f); close()
    }
    val System = icon("System") {
        moveTo(3f, 4f); horizontalLineTo(21f); verticalLineTo(16f); horizontalLineTo(3f); close()
        moveTo(12f, 16f); verticalLineTo(21f); moveTo(8f, 21f); horizontalLineTo(16f)
    }
}
