package com.nhnengineering.rftest.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the GeoPackage bytes against the specification.
 *
 * Every failure this format has produces a file that opens without complaint and is wrong:
 * transposed coordinates put the survey in the Gulf of Guinea, a missing `application_id` makes
 * QGIS show no layers with no error, and a flags byte claiming an absent envelope shifts the
 * geometry by 32 bytes. None of them look like failures.
 *
 * The bytes are decoded back independently here rather than compared against a recorded blob, so
 * the test states what the format means rather than what this implementation happened to emit.
 */
class GeoPackageFormatTest {

    private fun decode(blob: ByteArray): Triple<Int, Double, Double> {
        val bb = ByteBuffer.wrap(blob)
        assertEquals("magic G", 0x47.toByte(), bb.get())
        assertEquals("magic P", 0x50.toByte(), bb.get())
        assertEquals("version 0", 0x00.toByte(), bb.get())

        val flags = bb.get().toInt()
        // Bit 0 selects the header's byte order; bits 1..3 are the envelope indicator.
        bb.order(if (flags and 0x01 == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val envelopeCode = (flags shr 1) and 0x07
        assertEquals("no envelope declared", 0, envelopeCode)
        assertEquals("not flagged empty", 0, (flags shr 4) and 0x01)

        val srsId = bb.int

        val wkbOrder = bb.get().toInt()
        bb.order(if (wkbOrder == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        assertEquals("wkbPoint", 1, bb.int)

        val x = bb.double
        val y = bb.double
        return Triple(srsId, x, y)
    }

    @Test
    fun `a point encodes longitude first, then latitude`() {
        // The error that relocates an entire survey while leaving a readable file. Deliberately
        // asymmetric values so a transposition cannot pass.
        val (srs, x, y) = decode(GeoPackageFormat.pointBlob(lonDeg = -80.1397, latDeg = 26.0500))

        assertEquals(GeoPackageFormat.SRS_WGS84, srs)
        assertEquals("X must be longitude", -80.1397, x, 1e-9)
        assertEquals("Y must be latitude", 26.0500, y, 1e-9)
    }

    @Test
    fun `a point without an envelope is exactly 29 bytes`() {
        // 8 header + 21 WKB. A reader trusts the flags to know where the geometry starts, so an
        // unexpected length means the flags and the payload disagree.
        val blob = GeoPackageFormat.pointBlob(-80.0, 26.0)

        assertEquals(29, blob.size)
        assertEquals(GeoPackageFormat.HEADER_BYTES + GeoPackageFormat.WKB_POINT_BYTES, blob.size)
    }

    @Test
    fun `southern and western coordinates survive`() {
        // Negative doubles in little-endian order are where a hand-rolled writer usually breaks.
        for ((lon, lat) in listOf(
            -80.1397 to 26.0500,
            12.4924 to 41.8902,
            -0.0001 to -0.0001,
            179.9999 to -89.9,
            -179.9999 to 85.0,
        )) {
            val (_, x, y) = decode(GeoPackageFormat.pointBlob(lon, lat))
            assertEquals("lon $lon", lon, x, 1e-9)
            assertEquals("lat $lat", lat, y, 1e-9)
        }
    }

    @Test
    fun `the application id spells GPKG`() {
        // Without this in the SQLite header, QGIS opens the file and shows nothing, with no error
        // to explain it. The bytes must read as ASCII "GPKG".
        val id = GeoPackageFormat.APPLICATION_ID
        val bytes = byteArrayOf(
            ((id ushr 24) and 0xFF).toByte(),
            ((id ushr 16) and 0xFF).toByte(),
            ((id ushr 8) and 0xFF).toByte(),
            (id and 0xFF).toByte(),
        )

        assertEquals("GPKG", String(bytes, Charsets.US_ASCII))
    }

    @Test
    fun `the user version identifies GeoPackage 1_3`() {
        assertEquals(10_300, GeoPackageFormat.USER_VERSION)
    }

    @Test
    fun `a custom SRS id is carried into the header`() {
        val (srs, _, _) = decode(GeoPackageFormat.pointBlob(-80.0, 26.0, srsId = 3857))

        assertEquals(3857, srs)
    }

    @Test
    fun `the three mandatory tables are all present`() {
        val ddl = GeoPackageFormat.REQUIRED_TABLES.joinToString("\n")

        for (t in listOf("gpkg_spatial_ref_sys", "gpkg_contents", "gpkg_geometry_columns")) {
            assertTrue("$t must be created", ddl.contains(t))
        }
    }

    @Test
    fun `the mandatory spatial reference rows are present, including the undefined ones`() {
        // Two of the three describe nothing and exist only so a table may legally reference an
        // unknown system. A validator rejects the file without them even though nothing uses them.
        val ids = GeoPackageFormat.REQUIRED_SRS_ROWS.map { it[1] as Int }

        assertTrue("undefined cartesian (-1) required", ids.contains(-1))
        assertTrue("undefined geographic (0) required", ids.contains(0))
        assertTrue("WGS 84 required", ids.contains(4326))
        assertEquals(3, ids.size)
    }

    @Test
    fun `the WGS84 row carries a real WKT definition`() {
        // "undefined" here would be accepted by a lax reader and rejected by a strict one.
        val wgs84 = GeoPackageFormat.REQUIRED_SRS_ROWS.single { it[1] == 4326 }
        val definition = wgs84[4] as String

        assertTrue(definition.startsWith("GEOGCS"))
        assertTrue(definition.contains("WGS 84"))
        assertTrue(definition.contains("6378137"))
    }

    @Test
    fun `the feature table declares a geometry column named geom`() {
        // gpkg_geometry_columns will name this column, and the two must agree or the layer is
        // registered against a column that does not exist.
        val sql = GeoPackageFormat.createFeatureTableSql("samples")

        assertTrue(sql.contains("\"samples\""))
        assertTrue(sql.contains("geom BLOB"))
        assertTrue("needs an integer primary key for a feature table", sql.contains("fid INTEGER PRIMARY KEY"))
    }

    @Test
    fun `the feature table carries the fields a survey is analysed on`() {
        val sql = GeoPackageFormat.createFeatureTableSql("samples")

        for (col in listOf(
            "rsrp_dbm", "sinr_db", "rsrq_db", "cell_band", "rat", "serving_pci",
            "channel", "area", "floor", "gps_accuracy_m", "level_bucket",
        )) {
            assertTrue("$col must be exported", sql.contains(col))
        }
    }
}
