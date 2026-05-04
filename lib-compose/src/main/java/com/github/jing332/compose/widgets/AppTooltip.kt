package com.github.jing332.compose.widgets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTooltip(
    modifier: Modifier = Modifier,
    tooltip: String,
    content: @Composable (tooltip: String) -> Unit
) {
    val state = rememberTooltipState()

    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val centerX = anchorBounds.left +
                        ((anchorBounds.right - anchorBounds.left) - popupContentSize.width) / 2
                val x = centerX.coerceIn(0, maxX)

                val aboveY = anchorBounds.top - popupContentSize.height
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                val y = if (aboveY >= 0) {
                    aboveY
                } else {
                    anchorBounds.bottom.coerceAtMost(maxY)
                }

                return IntOffset(x, y)
            }
        }
    }

    TooltipBox(
        modifier = modifier,
        positionProvider = positionProvider,
        tooltip = {
            PlainTooltip {
                Text(tooltip)
            }
        },
        state = state,
        content = {
            content(tooltip)
        },
    )
}
