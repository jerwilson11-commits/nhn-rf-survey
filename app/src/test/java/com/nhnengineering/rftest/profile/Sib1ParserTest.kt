package com.nhnengineering.rftest.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are written in 3GPP TS 38.331 value notation with deliberately varied whitespace,
 * because that variation is the thing the parser exists to survive. What is held constant across
 * them is only what the spec fixes: the identifiers and the enumerated spellings.
 */
class Sib1ParserTest {

    /**
     * The n25 cell actually captured on the OnePlus on 2026-09-19. It is FDD, so it has no
     * tdd-UL-DL-ConfigurationCommon, and the parser must say so rather than leave the absence
     * looking like a failed parse.
     */
    private val realN25Fdd = """
        NR5G RRC OTA Packet -- NR RRC Release 15.10.0, PCI 929, Frequency 396250, BCCH_DL_SCH
        systemInformationBlockType1 : {
          cellSelectionInfo {
            q-RxLevMin -62
          },
          cellAccessRelatedInfo {
            plmn-IdentityInfoList {
              {
                plmn-IdentityList {
                  {
                    mcc { 3, 1, 0 },
                    mnc { 2, 6, 0 }
                  }
                },
                trackingAreaCode '000000000000000000000001'B,
                cellIdentity '0000000000000000000000000000000001'B
              }
            }
          },
          servingCellConfigCommon {
            uplinkConfigCommon {
              frequencyInfoUL {
                freqBandIndicatorNR 25,
                absoluteFrequencyPointA 379092
              }
            },
            scs-SpecificCarrierList {
              {
                subcarrierSpacing kHz15,
                carrierBandwidth 106
              }
            },
            p-Max 27,
            rach-ConfigCommon {
              prach-ConfigurationIndex 18,
              powerRampingStep dB4
            }
          }
        }
    """.trimIndent()

    /** A textbook n41 TDD carrier: 2.5 ms at 30 kHz, three down, one special, one up. */
    private val n41Tdd = """
        systemInformationBlockType1
          servingCellConfigCommon
            freqBandIndicatorNR 41
            scs-SpecificCarrierList
              subcarrierSpacing kHz30
              carrierBandwidth 273
            ssb-PositionsInBurst
              mediumBitmap '11110000'B
            ssb-periodicityServingCell ms20
            tdd-UL-DL-ConfigurationCommon
              referenceSubcarrierSpacing kHz30
              pattern1
                dl-UL-TransmissionPeriodicity ms2p5
                nrofDownlinkSlots 3
                nrofDownlinkSymbols 10
                nrofUplinkSlots 1
                nrofUplinkSymbols 2
    """.trimIndent()

    @Test
    fun `reads the real n25 capture`() {
        val r = Sib1Parser.parse(realN25Fdd)

        assertTrue(r.looksLikeSib1)
        assertEquals("n25", r.band)
        assertEquals(15, r.scsKhz)
        assertEquals(106, r.carrierBandwidthRb)
        assertEquals("310", r.mcc)
        assertEquals("260", r.mnc)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun `an FDD carrier is reported as having no slot pattern rather than as a failure`() {
        val r = Sib1Parser.parse(realN25Fdd)

        assertFalse(r.isTdd)
        assertNull(r.derivedPattern)
        assertNull(r.tddPeriodicityMs)
        assertTrue(r.notes.any { it.contains("FDD carrier") })
    }

    @Test
    fun `a bit string elsewhere in the message is not mistaken for the SSB bitmap`() {
        // The n25 fixture carries trackingAreaCode and cellIdentity as bit strings and no SSB
        // bitmap at all. Returning one of those would be a silent wrong answer.
        assertNull(Sib1Parser.parse(realN25Fdd).ssbPositionsInBurst)
    }

    @Test
    fun `derives DDDSU from a 2 point 5 ms period at 30 kHz`() {
        val r = Sib1Parser.parse(n41Tdd)

        assertTrue(r.isTdd)
        assertEquals("n41", r.band)
        assertEquals("2.5", r.tddPeriodicityMs)
        assertEquals(3, r.dlSlots)
        assertEquals(10, r.dlSymbols)
        assertEquals(1, r.ulSlots)
        assertEquals(2, r.ulSymbols)
        assertEquals("DDDSU", r.derivedPattern)
        assertEquals(20, r.ssbPeriodicityMs)
        assertEquals("11110000", r.ssbPositionsInBurst)
    }

    @Test
    fun `referenceSubcarrierSpacing is not read as subcarrierSpacing`() {
        // Both appear in the TDD fixture. If the word boundary were wrong, the carrier SCS would
        // silently pick up the reference SCS and the two could never be seen to disagree.
        val r = Sib1Parser.parse(n41Tdd)
        assertEquals(30, r.scsKhz)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun `unassigned slots are rendered flexible and called out`() {
        val text = """
            systemInformationBlockType1
            freqBandIndicatorNR 77
            subcarrierSpacing kHz30
            tdd-UL-DL-ConfigurationCommon
              dl-UL-TransmissionPeriodicity ms5
              nrofDownlinkSlots 6
              nrofDownlinkSymbols 6
              nrofUplinkSlots 2
              nrofUplinkSymbols 4
        """.trimIndent()

        val r = Sib1Parser.parse(text)

        // 5 ms at 30 kHz is 10 slots; 6 down + 1 special + 2 up uses 9, so one is flexible.
        assertEquals("DDDDDDSFUU", r.derivedPattern)
        assertTrue(r.notes.any { it.contains("1 slot in the period is flexible") })
    }

    @Test
    fun `a sub-millisecond period at high numerology still resolves`() {
        val text = """
            systemInformationBlockType1
            subcarrierSpacing kHz120
            tdd-UL-DL-ConfigurationCommon
              dl-UL-TransmissionPeriodicity ms0p625
              nrofDownlinkSlots 3
              nrofDownlinkSymbols 10
              nrofUplinkSlots 1
              nrofUplinkSymbols 2
        """.trimIndent()

        // 0.625 ms at 120 kHz is 5 slots.
        assertEquals("0.625", Sib1Parser.parse(text).tddPeriodicityMs)
        assertEquals("DDDSU", Sib1Parser.parse(text).derivedPattern)
    }

    @Test
    fun `slot counts that overflow the period derive no pattern and say why`() {
        val text = """
            systemInformationBlockType1
            subcarrierSpacing kHz30
            tdd-UL-DL-ConfigurationCommon
              dl-UL-TransmissionPeriodicity ms2p5
              nrofDownlinkSlots 7
              nrofDownlinkSymbols 10
              nrofUplinkSlots 4
              nrofUplinkSymbols 2
        """.trimIndent()

        val r = Sib1Parser.parse(text)

        assertNull(r.derivedPattern)
        assertTrue(r.notes.any { it.contains("holds 5") })
    }

    @Test
    fun `repeated identifiers that disagree fill nothing and report the disagreement`() {
        val text = """
            systemInformationBlockType1
            scs-SpecificCarrierList {
              { subcarrierSpacing kHz15, carrierBandwidth 52 },
              { subcarrierSpacing kHz30, carrierBandwidth 51 }
            }
        """.trimIndent()

        val r = Sib1Parser.parse(text)

        assertNull(r.scsKhz)
        assertNull(r.carrierBandwidthRb)
        assertEquals(2, r.conflicts.size)
        assertTrue(r.conflicts.any { it.startsWith("subcarrierSpacing appears 2 times") })
        assertFalse(r.found.contains(Sib1Parser.Field.SCS))
    }

    @Test
    fun `repeated identifiers that agree are used`() {
        val text = """
            systemInformationBlockType1
            uplinkConfigCommon { freqBandIndicatorNR 78 }
            downlinkConfigCommon { freqBandIndicatorNR 78 }
        """.trimIndent()

        val r = Sib1Parser.parse(text)

        assertEquals("n78", r.band)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun `pattern2 is flagged rather than quietly ignored`() {
        val text = n41Tdd + "\n      pattern2\n        dl-UL-TransmissionPeriodicity ms2p5"
        val r = Sib1Parser.parse(text)
        assertTrue(r.notes.any { it.contains("pattern2 is present") })
    }

    @Test
    fun `text that is not a SIB1 decode is refused with an explanation`() {
        val r = Sib1Parser.parse("Serving cell: n25, RSRP -94 dBm, SINR 12 dB")

        assertFalse(r.looksLikeSib1)
        assertFalse(r.anythingFound)
        assertTrue(r.notes.single().contains("does not look like a SIB1 decode"))
    }

    @Test
    fun `empty input is inert`() {
        val r = Sib1Parser.parse("   \n  ")
        assertFalse(r.looksLikeSib1)
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun `colons and equals signs between name and value are both accepted`() {
        val colon = Sib1Parser.parse("systemInformationBlockType1\nfreqBandIndicatorNR : 71")
        val equals = Sib1Parser.parse("systemInformationBlockType1\nfreqBandIndicatorNR = 71")
        val bare = Sib1Parser.parse("systemInformationBlockType1\nfreqBandIndicatorNR 71")

        assertEquals("n71", colon.band)
        assertEquals("n71", equals.band)
        assertEquals("n71", bare.band)
    }

    @Test
    fun `a hex SSB bitmap is kept in hex rather than guessed at`() {
        val text = "systemInformationBlockType1\nssb-PositionsInBurst { inOneGroup 'F0'H }"
        assertEquals("0xF0", Sib1Parser.parse(text).ssbPositionsInBurst)
    }

    @Test
    fun `what was not found is listed so the gap is visible`() {
        val r = Sib1Parser.parse(realN25Fdd)

        assertTrue(r.found.contains(Sib1Parser.Field.BAND))
        assertTrue(r.found.contains(Sib1Parser.Field.SCS))
        assertTrue(r.found.contains(Sib1Parser.Field.PLMN))
        assertTrue(r.missing.contains(Sib1Parser.Field.DL_SLOTS))
        assertTrue(r.missing.contains(Sib1Parser.Field.SSB_POSITIONS))
        assertEquals(Sib1Parser.Field.entries.size, r.found.size + r.missing.size)
    }
}
