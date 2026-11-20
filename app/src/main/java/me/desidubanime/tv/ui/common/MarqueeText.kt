package me.desidubanime.tv.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A one-line title that scrolls while its card holds focus.
 *
 * Anime names are long ("That Time I Got Reincarnated as a Slime Season 4")
 * and a two-line ellipsis on a 150dp tile cuts off the part that tells two
 * seasons apart. The marquee runs only for the focused card, so a row of
 * twenty tiles still costs one moving layer.
 *
 * [active] is the on/off switch; leaving focus snaps the text back to its
 * start instead of leaving it mid-word.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    Text(
        text,
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        // Unbounded width is what basicMarquee measures with; soft wrap would
        // defeat the scroll and reintroduce the clip.
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.basicMarquee(
            iterations = if (active) Int.MAX_VALUE else 0,
            repeatDelayMillis = 1_800,
            initialDelayMillis = 400,
            // Read from three metres away: slower than a phone's.
            velocity = 40.dp,
        ),
    )
}
