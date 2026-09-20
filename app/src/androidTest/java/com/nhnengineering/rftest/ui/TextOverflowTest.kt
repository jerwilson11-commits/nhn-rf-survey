package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Proves that [YieldingText] survives a row too narrow for its contents, and that a bare [Text] in
 * the same place destroys the reading beside it.
 *
 * ## What the layout engine actually does, which is not what it sounds like
 *
 * The obvious signal for this is `TextLayoutResult.hasVisualOverflow`, and it is the wrong one. It
 * was measured on the LE2115 before these assertions were written, and the row that shipped the
 * defect reports it as **false**:
 *
 * ```
 * "T-Mobile n41 Ericsson Conven…"   size = 420 x 147   hasVisualOverflow = false
 * "-104 dBm"                        size =   0 x 392   hasVisualOverflow = false   lines = 8
 * ```
 *
 * The long label consumed the whole 420 px row, the reading beside it was measured with
 * `maxWidth = 0`, and it smeared down eight lines one character at a time. Nothing overflowed: a
 * 0 x 392 box contains its content perfectly. The same probe also found `hasVisualOverflow` set on
 * healthy text -- "5G SA" laid out at 115 px inside a 900 px constraint reports it -- because a Row
 * measures weighted children more than once and the semantics action can replay a speculative pass.
 *
 * So the signal is not overflow. **It is a text squeezed to nothing.** That is unambiguous, it is
 * what the three shipped defects all did to their neighbour, and it cannot be produced by a healthy
 * layout.
 *
 * ## What this does not cover
 *
 * A label that wraps to three lines and makes a row three times taller is also a defect, and this
 * does not catch it -- there is no way to tell a deliberately multi-line paragraph from an
 * accidentally wrapped heading by looking at one text in isolation. `tools/layout_audit.py` covers
 * the source-level shape; this covers the behaviour. Neither alone is a proof.
 */
class TextOverflowTest {

    @get:Rule
    val rule = createComposeRule()

    /** A site name of the kind a floorplan file or a profile title actually carries. */
    private val longLabel = "T-Mobile n41 Ericsson Convention Centre West Hall Level 3"

    @Test
    fun a_bare_text_squeezes_the_reading_beside_it_to_nothing() {
        // The defect, built deliberately. If this stops failing to squeeze, the assertions below
        // are no longer testing anything and this test is the one that says so.
        rule.setContent {
            Row(Modifier.width(140.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(longLabel)
                Text("-104 dBm")
            }
        }

        assertEquals(
            "A bare Text beside a long one should have been squeezed to zero width. If it was " +
                "not, this test can no longer detect the defect it exists to detect.",
            listOf("-104 dBm"),
            rule.squeezedTexts(),
        )
    }

    @Test
    fun a_yielding_text_leaves_the_reading_intact() {
        rule.setContent {
            Row(Modifier.width(140.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                YieldingText(longLabel)
                Text("-104 dBm")
            }
        }

        assertEquals(emptyList<String>(), rule.squeezedTexts())
    }

    @Test
    fun the_label_gives_way_on_one_line_and_the_reading_keeps_its_width() {
        // The whole point of choosing which element yields: a truncated label is recoverable, a
        // destroyed measurement is a wrong reading.
        rule.setContent {
            Row(Modifier.width(140.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                YieldingText(longLabel)
                Text("-104 dBm")
            }
        }

        val laid = rule.textLayouts()
        val reading = laid.single { it.layoutInput.text.text == "-104 dBm" }
        val label = laid.single { it.layoutInput.text.text == longLabel }

        assertTrue("The reading was squeezed.", reading.size.width > 0)
        assertEquals("The reading wrapped instead of keeping its line.", 1, reading.lineCount)
        assertEquals("The label should ellipsise on one line, not wrap.", 1, label.lineCount)
        assertTrue(
            "The label should have given way rather than taking the whole row.",
            label.size.width < 140 * 3,
        )
    }

    @Test
    fun a_row_with_room_to_spare_is_left_alone() {
        rule.setContent {
            Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                YieldingText("Cellular")
                Text("5G SA")
            }
        }

        assertEquals(emptyList<String>(), rule.squeezedTexts())
        assertTrue(rule.textLayouts().all { it.lineCount == 1 })
    }
}

/** Every text layout currently on screen. */
private fun ComposeContentTestRule.textLayouts(): List<TextLayoutResult> {
    val out = mutableListOf<TextLayoutResult>()
    onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().forEach { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        out += results
    }
    return out
}

/**
 * Texts that have content but were given no width to put it in.
 *
 * Returns the strings rather than a count, because a failure naming the text is one somebody can
 * act on and a count is one they have to go and investigate.
 */
private fun ComposeContentTestRule.squeezedTexts(): List<String> =
    textLayouts()
        .filter { it.layoutInput.text.text.isNotBlank() && it.size.width <= 0 }
        .map { it.layoutInput.text.text }
