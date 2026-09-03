package com.nhnengineering.rftest.profile

import com.nhnengineering.rftest.report.SessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cross-check.
 *
 * The risk here is not missing a discrepancy — it is manufacturing one. This compares a human
 * record against a handset's partial view, and most of a TDD configuration is invisible to the
 * handset entirely. A cross-check that raised findings freely would train an engineer to ignore it,
 * which is worse than not having it.
 *
 * So most of these tests assert **silence**.
 */
class ProfileCrossCheckTest {

    private fun profile(
        band: String = "n41",
        site: String? = null,
        scs: Int? = 30,
        operator: String = "T-Mobile",
    ) = TddProfile(
        id = "p", vendor = "Ericsson", operator = operator, mcc = "310", mnc = "260",
        band = band, market = null, siteName = site,
        tddPattern = "DDDSU", tddPeriodicityMs = "2.5", dlSlots = 3, dlSymbols = 10,
        ulSlots = 1, ulSymbols = 2, ssbPeriodicityMs = 20, ssbPositionsInBurst = "10101010",
        scsKhz = scs, source = "Operator RF team", recordedAtUtcMillis = 1_756_000_000_000,
        note = null,
    )

    private fun layout(
        band: String = "n41",
        arrangement: SessionStats.SsbArrangement,
        positions: Int = 2,
        evidence: Boolean = true,
    ) = SessionStats.BandSsbLayout(
        band = band,
        positions = (1..positions).map {
            SessionStats.SsbPosition(
                channel = 500_000 + it, freqMhz = 2500.0 + it, gscn = 6000 + it,
                pcis = listOf(it * 100, it * 100 + 1, it * 100 + 2), samples = 50,
            )
        },
        arrangement = arrangement,
        sufficientEvidence = evidence,
    )

    @Test
    fun `no profile means nothing to check`() {
        val f = ProfileCrossCheck.check(
            null,
            listOf(layout(arrangement = SessionStats.SsbArrangement.PER_SECTOR)),
            "n41",
        )

        assertTrue(f.isEmpty())
    }

    @Test
    fun `a per-sector site under a general profile is flagged to check`() {
        // The finding this exists for: a vendor default applied to a building planned differently.
        val f = ProfileCrossCheck.check(
            profile(site = null),
            listOf(layout(arrangement = SessionStats.SsbArrangement.PER_SECTOR)),
            "n41",
        )

        val finding = f.single { it.headline.contains("varies by sector") }
        assertEquals(ProfileCrossCheck.Severity.CHECK, finding.severity)
        assertTrue(
            "must suggest recording a site override: ${finding.detail}",
            finding.detail.contains("site override"),
        )
    }

    @Test
    fun `a per-sector site with a site override already recorded says nothing`() {
        // Already known about. Repeating it every walk would be noise.
        val f = ProfileCrossCheck.check(
            profile(site = "Margaritaville"),
            listOf(layout(arrangement = SessionStats.SsbArrangement.PER_SECTOR)),
            "n41",
        )

        assertTrue(f.none { it.headline.contains("varies by sector") })
    }

    @Test
    fun `thin evidence does not become a finding`() {
        // A walk that missed some sectors looks exactly like a per-sector plan. Raising this on
        // one or two cells per position would teach the operator to ignore the check.
        val f = ProfileCrossCheck.check(
            profile(),
            listOf(layout(arrangement = SessionStats.SsbArrangement.PER_SECTOR, evidence = false)),
            "n41",
        )

        assertTrue(f.none { it.headline.contains("varies by sector") })
    }

    @Test
    fun `a shared PCI plan is reported as information, not as a problem`() {
        // Multiple carriers over the same sectors is ordinary. It is worth saying only because the
        // profile describes one configuration and there is more than one carrier present.
        val f = ProfileCrossCheck.check(
            profile(),
            listOf(layout(arrangement = SessionStats.SsbArrangement.SHARED_PCI_PLAN)),
            "n41",
        )

        val finding = f.single { it.headline.contains("More than one carrier") }
        assertEquals(ProfileCrossCheck.Severity.INFO, finding.severity)
    }

    @Test
    fun `a single SSB position on a matching band says nothing at all`() {
        // The common case. Silence rather than "no discrepancies found", which would overstate how
        // much was actually compared -- almost none of a TDD configuration is observable.
        val f = ProfileCrossCheck.check(
            profile(),
            listOf(layout(arrangement = SessionStats.SsbArrangement.SINGLE, positions = 1)),
            "n41",
        )

        assertTrue("expected silence, got $f", f.isEmpty())
    }

    @Test
    fun `a duplex mismatch means the profile is matched to the wrong band`() {
        // n41 is TDD and n71 is FDD. This cannot be a deployment choice, so it is a matching error.
        val f = ProfileCrossCheck.check(
            profile(band = "n71"),
            listOf(layout(arrangement = SessionStats.SsbArrangement.SINGLE, positions = 1)),
            "n41",
        )

        val finding = f.single { it.headline.contains("Duplex") }
        assertEquals(ProfileCrossCheck.Severity.CHECK, finding.severity)
        assertTrue(finding.detail.contains("wrong band"))
    }

    @Test
    fun `a subcarrier spacing difference is a prompt, never a discrepancy`() {
        // Both sides are conventions: the app infers from the band, the profile is what somebody
        // was told. Presenting that as a contradiction would be dishonest in both directions.
        val f = ProfileCrossCheck.check(
            profile(scs = 15),
            listOf(layout(arrangement = SessionStats.SsbArrangement.SINGLE, positions = 1)),
            "n41",
        )

        val finding = f.single { it.headline.contains("Subcarrier") }
        assertEquals(ProfileCrossCheck.Severity.INFO, finding.severity)
        assertTrue(
            "must state that neither side is measured: ${finding.detail}",
            finding.detail.contains("Neither is measured"),
        )
    }

    @Test
    fun `an ambiguous band label still finds its layout`() {
        // The app labels an overlapping channel "n2/n25". The cross-check must not silently do
        // nothing because it failed to line the two up.
        val f = ProfileCrossCheck.check(
            profile(band = "n25"),
            listOf(layout(band = "n25", arrangement = SessionStats.SsbArrangement.PER_SECTOR)),
            "n2/n25",
        )

        assertTrue(f.any { it.headline.contains("varies by sector") })
    }

    @Test
    fun `nothing is claimed about the slot pattern, which cannot be observed`() {
        // The most important silence. A profile claiming DDDSU is neither confirmed nor
        // contradicted by anything a handset measures, and a check that implied otherwise would
        // put a false confirmation next to a value destined for a repeater.
        val f = ProfileCrossCheck.check(
            profile(),
            listOf(layout(arrangement = SessionStats.SsbArrangement.PER_SECTOR)),
            "n41",
        )

        for (finding in f) {
            val text = finding.headline + " " + finding.detail
            assertTrue("must not mention the slot pattern: $text", !text.contains("DDDSU"))
            assertTrue("must not claim to check periodicity: $text", !text.contains("periodicity"))
        }
    }
}
