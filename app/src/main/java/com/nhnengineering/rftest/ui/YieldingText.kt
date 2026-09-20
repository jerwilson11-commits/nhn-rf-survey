package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * The one item in a row that gives up width when the row runs short.
 *
 * ## Why this exists
 *
 * Three text-collision defects shipped inside one week: an ARFCN overflowing its field, the map
 * strip clipping the word "PCI", and a verdict headline wrapping into its own marker. Three
 * instances of one shape is not bad luck, it is a missing rule -- and the shape was always the
 * same. A [androidx.compose.foundation.layout.Row] holds two or more texts, none of them is told
 * what to do when the row is narrower than their sum, so Compose lets them collide or run off the
 * edge. On the 411 dp phone the layouts were written against there is usually room. On a narrower
 * screen, at a larger font scale, or the first time a value is longer than the developer imagined,
 * there is not.
 *
 * ## The rule
 *
 * **In any row holding a variable-length text, exactly one of them is a [YieldingText].** That one
 * shrinks and ellipsises; everything else keeps the width it asks for.
 *
 * Which one yields is not arbitrary, and the choice is the whole point of naming it rather than
 * scattering modifiers. **The measurement never yields.** A truncated label is recoverable -- the
 * reader knows what a signal card is showing. A truncated number is a wrong reading, and a wrong
 * reading in a survey tool is worse than a missing one. So the label yields, the unit suffix
 * yields, the site name yields; the dBm figure, the throughput and the elapsed timer do not.
 *
 * ## Why it is a composable rather than a modifier
 *
 * Three things have to be true together -- `weight(1f, fill = false)`, `maxLines = 1` and an
 * ellipsis -- and every one of the three shipped defects had some of them and not the others.
 * Bundling them into something with a name means there is one thing to remember instead of three,
 * and a reviewer can see the rule was applied by reading the call rather than by checking three
 * arguments.
 *
 * `fill = false` matters: it lets the text take less than its share when it is short, so a row with
 * a two-character label does not push the value into the middle of the row.
 */
@Composable
fun RowScope.YieldingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier.weight(1f, fill = false),
        style = style,
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        fontFamily = fontFamily,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
