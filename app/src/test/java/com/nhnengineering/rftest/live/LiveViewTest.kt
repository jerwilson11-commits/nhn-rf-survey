package com.nhnengineering.rftest.live

import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the live view's JavaScript against the Kotlin it duplicates.
 *
 * The page carries its own copy of the colour scales because it is served as a static string to a
 * browser and cannot call into `RsrpBucket`. That duplication is accepted, but not on trust: if the
 * Kotlin thresholds move and the page's do not, the operator sees one colour on the laptop while
 * the report prints another for the same sample — and they would have no reason to doubt either.
 * These tests make that a failing build.
 */
class LiveViewTest {

    /** Extracts `{ min: -85, color: '#2E7D32', ... }` entries from a named JS array. */
    private fun scaleFrom(name: String): List<Pair<Int, String>> {
        val array = Regex("$name\\s*=\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(LivePage.HTML)
            ?.groupValues?.get(1)
            ?: error("$name not found in the live page")
        return Regex("min:\\s*(-?\\d+),\\s*color:\\s*'(#[0-9A-Fa-f]{6})'")
            .findAll(array)
            .map { it.groupValues[1].toInt() to it.groupValues[2].uppercase() }
            .toList()
    }

    /** `RsrpBucket.argb` is 0xAARRGGBB; the page uses CSS #RRGGBB. */
    private fun css(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    @Test
    fun `the page's RSRP scale matches RsrpBucket`() {
        val page = scaleFrom("RSRP_SCALE")
        val kotlin = RsrpBucket.entries

        assertEquals("bucket count", kotlin.size, page.size)
        kotlin.forEachIndexed { i, bucket ->
            assertEquals("colour of ${bucket.name}", css(bucket.argb), page[i].second)
            // The final bucket is the catch-all. Kotlin uses Int.MIN_VALUE, which will not survive
            // a round trip through JavaScript, so the page uses a sentinel far below any real
            // measurement instead. Every other threshold must match exactly.
            if (i < kotlin.size - 1) {
                assertEquals("threshold of ${bucket.name}", bucket.minDbm, page[i].first)
            } else {
                assertTrue(
                    "the catch-all must sit below any measurable level",
                    page[i].first < -160,
                )
            }
        }
    }

    @Test
    fun `the page's Wi-Fi scale matches RssiBucket`() {
        val page = scaleFrom("RSSI_SCALE")
        val kotlin = RssiBucket.entries

        assertEquals("bucket count", kotlin.size, page.size)
        kotlin.forEachIndexed { i, bucket ->
            assertEquals("colour of ${bucket.name}", css(bucket.argb), page[i].second)
            if (i < kotlin.size - 1) {
                assertEquals("threshold of ${bucket.name}", bucket.minDbm, page[i].first)
            }
        }
    }

    @Test
    fun `the two scales are not the same, as on every other surface`() {
        // Reusing the Wi-Fi scale for RSRP would paint a healthy DAS red. The report, the handset
        // UI and the exporters all keep them separate; the live view has to as well.
        assertTrue(scaleFrom("RSRP_SCALE").map { it.first } != scaleFrom("RSSI_SCALE").map { it.first })
    }

    @Test
    fun `satellite tiles are fetched from the phone, never from the internet directly`() {
        // The laptop has no internet of its own; the phone proxies tiles over the connection being
        // measured. A hard-coded provider URL in the page would work on a desk and fail in a
        // basement, which is the only place it matters.
        assertTrue(
            "tiles must be requested from the phone's own /tile route",
            LivePage.HTML.contains("'/tile/'"),
        )
        assertTrue(
            "the page must not reach a tile provider directly",
            !LivePage.HTML.contains("arcgisonline") && !LivePage.HTML.contains("tile.openstreetmap"),
        )
    }

    @Test
    fun `imagery carries its attribution`() {
        // Required by the provider's terms, and the page is the only place a viewer sees it.
        assertTrue("Esri attribution must appear", LivePage.HTML.contains("Esri"))
    }

    @Test
    fun `the page is self-contained`() {
        // The laptop is cabled to a phone in a basement and has no internet. Anything the page
        // fetches from elsewhere is a blank panel at the moment it is needed.
        val offenders = listOf("http://", "https://", "//cdn", "<script src", "<link ")
            .filter { LivePage.HTML.contains(it) && it !in listOf("http://") }
        // http:// appears once, in the instruction text telling the operator which URL to open.
        assertTrue(
            "page must not load remote resources, found: $offenders",
            offenders.isEmpty(),
        )
    }

    // ---- JSON encoding ----------------------------------------------------

    @Test
    fun `strings with quotes are escaped rather than breaking the document`() {
        // An SSID is vendor-supplied and an area label is typed by the operator mid-walk. One
        // unescaped quote yields JSON the page cannot parse, which presents as a display frozen
        // at the last good poll -- indistinguishable from a crashed app, while recording.
        val server = LiveServer()

        assertEquals("\"Bob's \\\"Guest\\\" AP\"", server.jsonString("Bob's \"Guest\" AP"))
        assertEquals("\"back\\\\slash\"", server.jsonString("back\\slash"))
        assertEquals("\"line\\nbreak\"", server.jsonString("line\nbreak"))
        assertEquals("null", server.jsonString(null))
    }

    @Test
    fun `control characters are escaped, not emitted raw`() {
        assertEquals("\"a\\u0001b\"", LiveServer().jsonString("a\u0001b"))
    }

    @Test
    fun `numbers use a decimal point regardless of locale`() {
        // The same defect that corrupted the CSV in Phase 2, in a different output format: a
        // comma decimal separator turns one JSON number into two and shifts the object.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val encoded = LiveServer().num(-80.5)
            assertTrue("expected a decimal point, got $encoded", encoded.contains('.'))
            assertTrue("must not contain a comma: $encoded", !encoded.contains(','))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `non-finite numbers become null rather than invalid JSON`() {
        // NaN and Infinity are not JSON. They would reach here from a distance calculation that
        // divided by zero, and would silently break the whole feed.
        assertEquals("null", LiveServer().num(Double.NaN))
        assertEquals("null", LiveServer().num(Double.POSITIVE_INFINITY))
    }

    // ---- Binding ----------------------------------------------------------

    @Test
    fun `the server binds loopback only, never every interface`() {
        // Binding 0.0.0.0 would serve real-time position, serving cell and signal level to anyone
        // else on the venue Wi-Fi. Passing null to ServerSocket does exactly that and would read
        // as a harmless simplification in a diff, so the decision is pinned here.
        val address = LiveServer().bindAddress

        assertTrue("bind address must be loopback, was $address", address.isLoopbackAddress)
        assertTrue("bind address must not be a wildcard", !address.isAnyLocalAddress)
    }
}
