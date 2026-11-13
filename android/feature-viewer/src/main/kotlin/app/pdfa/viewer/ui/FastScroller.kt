package app.pdfa.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pdfa.feature.viewer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val ThumbHeight = 56.dp
private val TouchWidth = 48.dp
private const val HIDE_AFTER_MS = 1400L

/**
 * Fast-scroll thumb on the trailing edge with a page badge. Visible while scrolling or dragging,
 * fades afterwards. [fraction] is 0..1 of the scroll range; [badge] is the text such as "12 / 340".
 */
@Composable
fun FastScroller(
    fractionProvider: () -> Float,
    badge: String,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // The fraction changes every scroll frame: it is read in effects and layout lambdas, never in composition.
    LaunchedEffect(dragging) {
        snapshotFlow { fractionProvider() }.collectLatest {
            visible = true
            if (!dragging) {
                delay(HIDE_AFTER_MS)
                visible = false
            }
        }
    }
    val provider by rememberUpdatedState(fractionProvider)
    val onFractionNow by rememberUpdatedState(onFraction)
    val position = stringResource(R.string.viewer_scroll_position)

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val trackPx = with(LocalDensity.current) { (maxHeight - ThumbHeight).toPx() }.coerceAtLeast(1f)
        AnimatedVisibility(
            visible = visible || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (provider().coerceIn(0f, 1f) * trackPx).roundToInt()) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelLarge,
                        // One line, never clipped: the pill grows with the font scale (checked at 200%).
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .size(width = TouchWidth, height = ThumbHeight)
                        .semantics {
                            contentDescription = position
                            stateDescription = badge
                            progressBarRangeInfo = ProgressBarRangeInfo(provider(), 0f..1f)
                            setProgress { f -> onFractionNow(f); true }
                        }
                        .pointerInput(trackPx) {
                            var current = 0f
                            detectDragGestures(
                                onDragStart = { dragging = true; current = provider() },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, drag ->
                                change.consume()
                                current = (current + drag.y / trackPx).coerceIn(0f, 1f)
                                onFractionNow(current)
                            }
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .width(8.dp)
                            .height(ThumbHeight)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}
