package io.github.r1b2ns.stackaudit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object Icons {
    private fun icon(
        name: String,
        build: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        block: PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float,
    ) {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arcToRelative(r, r, 0f, false, true, r, r)
        lineTo(right, bottom - r)
        arcToRelative(r, r, 0f, false, true, -r, r)
        lineTo(left + r, bottom)
        arcToRelative(r, r, 0f, false, true, -r, -r)
        lineTo(left, top + r)
        arcToRelative(r, r, 0f, false, true, r, -r)
        close()
    }

    val ArrowUpRight =
        icon("arrowUpRight") {
            stroke(2.6f) {
                moveTo(7f, 17f)
                lineTo(17f, 7f)
                moveTo(9f, 7f)
                lineTo(17f, 7f)
                lineTo(17f, 15f)
            }
        }

    val ArrowDownLeft =
        icon("arrowDownLeft") {
            stroke(2.6f) {
                moveTo(17f, 7f)
                lineTo(7f, 17f)
                moveTo(15f, 17f)
                lineTo(7f, 17f)
                lineTo(7f, 9f)
            }
        }

    val ArrowUp =
        icon("arrowUp") {
            stroke(3f) {
                moveTo(12f, 20f)
                lineTo(12f, 4.5f)
                moveTo(5.5f, 11f)
                lineTo(12f, 4.5f)
                lineTo(18.5f, 11f)
            }
        }

    val ArrowDown =
        icon("arrowDown") {
            stroke(3f) {
                moveTo(12f, 4f)
                lineTo(12f, 19.5f)
                moveTo(5.5f, 13f)
                lineTo(12f, 19.5f)
                lineTo(18.5f, 13f)
            }
        }

    val ChevronRight =
        icon("chevronRight") {
            stroke(2.6f) {
                moveTo(9f, 4.5f)
                lineTo(16.5f, 12f)
                lineTo(9f, 19.5f)
            }
        }

    val ChevronLeft =
        icon("chevronLeft") {
            stroke(2.6f) {
                moveTo(15.5f, 3.5f)
                lineTo(7f, 12f)
                lineTo(15.5f, 20.5f)
            }
        }

    val Share =
        icon("share") {
            stroke(1.8f) {
                moveTo(12f, 2.5f)
                lineTo(12f, 15f)
                moveTo(8f, 6.5f)
                lineTo(12f, 2.5f)
                lineTo(16f, 6.5f)
                moveTo(8.5f, 9.5f)
                lineTo(6.5f, 9.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, false, -1.5f, 1.5f)
                lineTo(5f, 20f)
                arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, 1.5f)
                lineTo(17.5f, 21.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, -1.5f)
                lineTo(19f, 11f)
                arcToRelative(1.5f, 1.5f, 0f, false, false, -1.5f, -1.5f)
                lineTo(15.5f, 9.5f)
            }
        }

    val Gear =
        icon("gear") {
            stroke(1.8f) {
                val teeth = 8
                val outer = 10f
                val inner = 7.6f
                val step = 2 * PI / teeth
                for (i in 0 until teeth) {
                    val center = i * step
                    val points =
                        listOf(
                            inner to center - step * 0.5,
                            inner to center - step * 0.26,
                            outer to center - step * 0.17,
                            outer to center + step * 0.17,
                            inner to center + step * 0.26,
                        )
                    points.forEachIndexed { index, (radius, angle) ->
                        val x = 12f + radius * cos(angle).toFloat()
                        val y = 12f + radius * sin(angle).toFloat()
                        if (i == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                close()
                circle(12f, 12f, 3.2f)
            }
        }

    val Copy =
        icon("copy") {
            stroke(1.8f) {
                roundRect(8.5f, 8.5f, 20f, 20f, 2f)
                moveTo(15.5f, 8.5f)
                lineTo(15.5f, 6f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
                lineTo(6f, 4f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                lineTo(4f, 13.5f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                lineTo(8.5f, 15.5f)
            }
        }

    val Link =
        icon("link") {
            stroke(2f) {
                moveTo(9.5f, 14.5f)
                lineTo(14.5f, 9.5f)
                moveTo(11f, 7f)
                lineTo(13f, 5f)
                arcToRelative(4f, 4f, 0f, false, true, 6f, 6f)
                lineTo(17f, 13f)
                moveTo(13f, 17f)
                lineTo(11f, 19f)
                arcToRelative(4f, 4f, 0f, false, true, -6f, -6f)
                lineTo(7f, 11f)
            }
        }

    val Search =
        icon("search") {
            stroke(2.2f) {
                circle(10.5f, 10.5f, 6.5f)
                moveTo(15.5f, 15.5f)
                lineTo(20.5f, 20.5f)
            }
        }

    val Checkmark =
        icon("checkmark") {
            stroke(2.8f) {
                moveTo(4.5f, 12.5f)
                lineTo(9.5f, 17.5f)
                lineTo(19.5f, 6.5f)
            }
        }

    val Lock =
        icon("lock") {
            stroke(2.4f) {
                moveTo(8f, 11f)
                lineTo(8f, 8f)
                arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
                lineTo(16f, 11f)
            }
            solid { roundRect(5f, 10.5f, 19f, 21.5f, 2.5f) }
        }

    val Shield =
        icon("shield") {
            solid {
                moveTo(12f, 2f)
                lineTo(20f, 5f)
                lineTo(20f, 11f)
                curveTo(20f, 16.2f, 16.5f, 20.2f, 12f, 22f)
                curveTo(7.5f, 20.2f, 4f, 16.2f, 4f, 11f)
                lineTo(4f, 5f)
                close()
            }
        }

    val Warning =
        icon("warning") {
            solid {
                moveTo(12f, 2.5f)
                lineTo(22f, 20.5f)
                lineTo(2f, 20.5f)
                close()
            }
        }

    val Flag =
        icon("flag") {
            stroke(1.9f) {
                moveTo(5.5f, 21.5f)
                lineTo(5.5f, 3.5f)
                moveTo(5.5f, 4f)
                lineTo(18.5f, 4f)
                lineTo(15.5f, 8.5f)
                lineTo(18.5f, 13f)
                lineTo(5.5f, 13f)
            }
        }

    val Trash =
        icon("trash") {
            stroke(1.8f) {
                moveTo(3.5f, 6.5f)
                lineTo(20.5f, 6.5f)
                moveTo(9f, 6.5f)
                lineTo(9f, 3.5f)
                lineTo(15f, 3.5f)
                lineTo(15f, 6.5f)
                moveTo(5.5f, 6.5f)
                lineTo(6.5f, 20.5f)
                lineTo(17.5f, 20.5f)
                lineTo(18.5f, 6.5f)
            }
        }

    val Phone =
        icon("phone") {
            stroke(1.8f) {
                roundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.5f)
                moveTo(10.5f, 18.5f)
                lineTo(13.5f, 18.5f)
            }
        }

    val Cloud =
        icon("cloudSlash") {
            stroke(1.8f) {
                moveTo(7f, 18f)
                arcToRelative(4f, 4f, 0f, false, true, 0f, -8f)
                arcToRelative(5.5f, 5.5f, 0f, false, true, 10.5f, -1f)
                arcToRelative(4.5f, 4.5f, 0f, false, true, 0f, 9f)
                close()
                moveTo(3f, 3f)
                lineTo(21f, 21f)
            }
        }

    val Close =
        icon("close") {
            stroke(2.2f) {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }

    val Clock =
        icon("clock") {
            stroke(1.8f) {
                circle(12f, 12f, 9f)
                moveTo(12f, 7f)
                lineTo(12f, 12f)
                lineTo(15.5f, 14f)
            }
        }

    val Doc =
        icon("doc") {
            stroke(1.8f) {
                roundRect(5f, 3f, 19f, 21f, 2f)
                moveTo(8.5f, 8f)
                lineTo(15.5f, 8f)
                moveTo(8.5f, 12f)
                lineTo(15.5f, 12f)
                moveTo(8.5f, 16f)
                lineTo(12.5f, 16f)
            }
        }

    val List =
        icon("list") {
            stroke(1.9f) {
                moveTo(8.5f, 6f)
                lineTo(20.5f, 6f)
                moveTo(8.5f, 12f)
                lineTo(20.5f, 12f)
                moveTo(8.5f, 18f)
                lineTo(20.5f, 18f)
                moveTo(4f, 6f)
                lineTo(4.01f, 6f)
                moveTo(4f, 12f)
                lineTo(4.01f, 12f)
                moveTo(4f, 18f)
                lineTo(4.01f, 18f)
            }
        }

    val Drive =
        icon("drive") {
            stroke(1.8f) {
                roundRect(3f, 13f, 21f, 20f, 2f)
                moveTo(5f, 13f)
                lineTo(7.5f, 5f)
                lineTo(16.5f, 5f)
                lineTo(19f, 13f)
                moveTo(16.5f, 16.5f)
                lineTo(16.51f, 16.5f)
            }
        }
}
