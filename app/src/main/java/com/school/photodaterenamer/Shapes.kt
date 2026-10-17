package com.school.photodaterenamer

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Custom angled "Jersey Cut" shape that clips specific corners at a 45-degree athletic angle
 * while keeping the remaining corners sharp.
 */
class JerseyCutShape(
    private val topStartCut: Dp = 0.dp,
    private val topEndCut: Dp = 16.dp,
    private val bottomEndCut: Dp = 0.dp,
    private val bottomStartCut: Dp = 16.dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val ts = with(density) { topStartCut.toPx() }.coerceAtMost(size.minDimension / 2f)
        val te = with(density) { topEndCut.toPx() }.coerceAtMost(size.minDimension / 2f)
        val be = with(density) { bottomEndCut.toPx() }.coerceAtMost(size.minDimension / 2f)
        val bs = with(density) { bottomStartCut.toPx() }.coerceAtMost(size.minDimension / 2f)

        val topLeft = if (layoutDirection == LayoutDirection.Ltr) ts else te
        val topRight = if (layoutDirection == LayoutDirection.Ltr) te else ts
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) be else bs
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bs else be

        val path = Path().apply {
            moveTo(topLeft, 0f)
            lineTo(size.width - topRight, 0f)
            if (topRight > 0f) lineTo(size.width, topRight)
            lineTo(size.width, size.height - bottomRight)
            if (bottomRight > 0f) lineTo(size.width - bottomRight, size.height)
            lineTo(bottomLeft, size.height)
            if (bottomLeft > 0f) lineTo(0f, size.height - bottomLeft)
            lineTo(0f, topLeft)
            if (topLeft > 0f) close() else close()
        }
        return Outline.Generic(path)
    }
}

// Reusable Match-Day Jersey Cut Presets
val JerseyCardShape = JerseyCutShape(
    topStartCut = 0.dp,
    topEndCut = 18.dp,
    bottomEndCut = 0.dp,
    bottomStartCut = 14.dp
)

val JerseyButtonShape = JerseyCutShape(
    topStartCut = 12.dp,
    topEndCut = 0.dp,
    bottomEndCut = 12.dp,
    bottomStartCut = 0.dp
)

val JerseyChipShape = JerseyCutShape(
    topStartCut = 0.dp,
    topEndCut = 8.dp,
    bottomEndCut = 0.dp,
    bottomStartCut = 8.dp
)

val JerseyThumbShape = JerseyCutShape(
    topStartCut = 0.dp,
    topEndCut = 10.dp,
    bottomEndCut = 0.dp,
    bottomStartCut = 10.dp
)

val AppShapes = Shapes(
    extraSmall = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp),
    small = CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp),
    medium = CutCornerShape(topEnd = 16.dp, bottomStart = 12.dp),
    large = CutCornerShape(topEnd = 20.dp, bottomStart = 16.dp),
    extraLarge = CutCornerShape(topEnd = 28.dp, bottomStart = 20.dp)
)
