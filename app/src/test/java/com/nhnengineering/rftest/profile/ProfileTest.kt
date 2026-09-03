package com.nhnengineering.rftest.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the profile library.
 *
 * Everything here was typed by a person after being told it, and cannot be reconstructed from a
 * measurement if it is lost or mangled. That makes the round trip and the matching rules the two
 * things worth testing hardest — a wrong match is worse than no match, because a slot pattern from
 * the wrong vendor configured into a repeater causes interference rather than an obvious failure.
 */
class ProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun profile(
        id: String = "1",
        vendor: String = "Ericsson",
        operator: String = "T-Mobile",
        mcc: String? = "310",
        mnc: String? = "260",
        band: String = "n41",
        market: String? = null,
        site: String? = null,
        note: String? = null,
    ) = TddProfile(
        id = id, vendor = vendor, operator = operator, mcc = mcc, mnc = mnc, band = band,
        market = market, siteName = site,
        tddPattern = "DDDSU", tddPeriodicityMs = "2.5", dlSlots = 3, dlSymbols = 10,
        ulSlots = 1, ulSymbols = 2, ssbPeriodicityMs = 20, ssbPositionsInBurst = "10101010",
        scsKhz = 30, source = "Operator RF team, email 2026-09-02",
        recordedAtUtcMillis = 1_756_000_000_000, note = note,
    )

    // ---- storage ---------------------------------------------------------

    @Test
    fun `a profile round-trips through the file`() {
        val store = ProfileStore(tmp.newFile("profiles.jsonl"))
        val p = profile()

        store.save(listOf(p))
        val back = store.load()

        assertEquals(0, back.skipped)
        assertEquals(p, back.profiles.single())
    }

    @Test
    fun `free text with quotes, commas and newlines survives`() {
        // Vendor names, sources and notes are typed by hand. One unescaped quote in a note must
        // not be able to shorten the record or shift the fields after it.
        val store = ProfileStore(tmp.newFile("profiles.jsonl"))
        val nasty = profile(
            vendor = "Ericsson \"Baseband 6630\", rev 2",
            note = "Confirmed by Dave.\nSecond line, with a comma \\ backslash",
        )

        store.save(listOf(nasty))

        assertEquals(nasty, store.load().profiles.single())
    }

    @Test
    fun `one corrupt line loses one profile, not the library`() {
        val f = tmp.newFile("profiles.jsonl")
        val store = ProfileStore(f)
        store.save(listOf(profile(id = "1"), profile(id = "2")))
        f.appendText("{this is not json\n")

        val result = store.load()

        assertEquals(2, result.profiles.size)
        assertEquals("a skipped line must be reported, not hidden", 1, result.skipped)
    }

    @Test
    fun `loading a library that does not exist yet is empty, not an error`() {
        val result = ProfileStore(tmp.newFile("absent.jsonl").also { it.delete() }).load()

        assertTrue(result.profiles.isEmpty())
        assertEquals(0, result.skipped)
    }

    @Test
    fun `upsert replaces by id rather than appending a duplicate`() {
        val store = ProfileStore(tmp.newFile("profiles.jsonl"))
        store.upsert(profile(id = "1", vendor = "Ericsson"))
        store.upsert(profile(id = "1", vendor = "Nokia"))

        val all = store.load().profiles

        assertEquals(1, all.size)
        assertEquals("Nokia", all.single().vendor)
    }

    @Test
    fun `delete removes only the named profile`() {
        val store = ProfileStore(tmp.newFile("profiles.jsonl"))
        store.save(listOf(profile(id = "1"), profile(id = "2")))

        store.delete("1")

        assertEquals("2", store.load().profiles.single().id)
    }

    // ---- matching --------------------------------------------------------

    @Test
    fun `matches on network code and band`() {
        val p = profile()
        val m = ProfileMatcher.match(
            listOf(p),
            ProfileMatcher.Query(mcc = "310", mnc = "260", operator = null, band = "n41"),
        )

        assertEquals(p, m)
    }

    @Test
    fun `a different operator is no match rather than a near one`() {
        // A wrong slot pattern is worse than none. Verizon's profile must never answer for
        // T-Mobile just because the band lines up.
        val m = ProfileMatcher.match(
            listOf(profile(mcc = "311", mnc = "480", operator = "Verizon")),
            ProfileMatcher.Query(mcc = "310", mnc = "260", operator = "T-Mobile", band = "n41"),
        )

        assertNull(m)
    }

    @Test
    fun `a different band is no match`() {
        val m = ProfileMatcher.match(
            listOf(profile(band = "n71")),
            ProfileMatcher.Query("310", "260", "T-Mobile", "n41"),
        )

        assertNull(m)
    }

    @Test
    fun `an ambiguous band label still matches the specific profile`() {
        // The app labels an overlapping channel "n2/n25" because the ARFCN cannot resolve it. A
        // profile recorded as n25 should answer for that, or the ambiguity the app is careful to
        // preserve would make its own library useless.
        val p = profile(band = "n25")
        val m = ProfileMatcher.match(
            listOf(p),
            ProfileMatcher.Query("310", "260", "T-Mobile", "n2/n25"),
        )

        assertEquals(p, m)
    }

    @Test
    fun `a site override beats the general profile`() {
        // The whole reason overrides exist: dense venues vary from the vendor default, and the
        // venues are where the survey work is.
        val general = profile(id = "general")
        val site = profile(id = "stadium", site = "Margaritaville")

        val m = ProfileMatcher.match(
            listOf(general, site),
            ProfileMatcher.Query("310", "260", "T-Mobile", "n41", siteName = "Margaritaville"),
        )

        assertEquals("stadium", m!!.id)
    }

    @Test
    fun `a site override never answers for a different site`() {
        // The failure that would matter most: a stadium's non-standard SSB plan quietly applied to
        // an office block down the road.
        val site = profile(id = "stadium", site = "Margaritaville")

        val m = ProfileMatcher.match(
            listOf(site),
            ProfileMatcher.Query("310", "260", "T-Mobile", "n41", siteName = "Some Office"),
        )

        assertNull(m)
    }

    @Test
    fun `a market profile beats a generic one`() {
        val generic = profile(id = "generic")
        val miami = profile(id = "miami", market = "South Florida")

        val m = ProfileMatcher.match(
            listOf(generic, miami),
            ProfileMatcher.Query("310", "260", "T-Mobile", "n41", market = "South Florida"),
        )

        assertEquals("miami", m!!.id)
    }

    @Test
    fun `operator name matches when the network codes are unknown`() {
        // A profile entered before the app ever saw that network has no MCC/MNC to match on.
        val p = profile(mcc = null, mnc = null)
        val m = ProfileMatcher.match(
            listOf(p),
            ProfileMatcher.Query(mcc = null, mnc = null, operator = "t-mobile", band = "n41"),
        )

        assertEquals(p, m)
    }

    @Test
    fun `no band means no match, because a pattern without a band is meaningless`() {
        assertNull(
            ProfileMatcher.match(
                listOf(profile()),
                ProfileMatcher.Query("310", "260", "T-Mobile", band = null),
            ),
        )
    }

    @Test
    fun `an empty library matches nothing without failing`() {
        assertNull(
            ProfileMatcher.match(emptyList(), ProfileMatcher.Query("310", "260", "T-Mobile", "n41")),
        )
    }

    @Test
    fun `a profile with nothing filled in is recognised as empty`() {
        val shell = profile().copy(
            tddPattern = null, tddPeriodicityMs = null, dlSlots = null, dlSymbols = null,
            ulSlots = null, ulSymbols = null, ssbPeriodicityMs = null,
            ssbPositionsInBurst = null, scsKhz = null,
        )

        assertTrue(shell.isEmpty)
        assertTrue(!profile().isEmpty)
    }
}
