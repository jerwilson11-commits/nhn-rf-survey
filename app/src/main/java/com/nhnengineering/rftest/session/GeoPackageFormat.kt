package com.nhnengineering.rftest.session

/**
 * The byte-level and schema-level parts of the GeoPackage format, kept pure so they can be tested.
 *
 * ## Why this is separated from the writing
 *
 * A GeoPackage is a SQLite database, and `android.database.sqlite` is a stub in JVM unit tests. If
 * the encoding lived alongside the database calls, none of it could be tested — and the parts that
 * go wrong in this format are exactly the parts a test would catch:
 *
 * - **Coordinate order.** WKB is X then Y, which is longitude then latitude. Reversed, every point
 *   lands in the Gulf of Guinea and the file still opens without complaint.
 * - **The `application_id` pragma.** Without `GPKG` in the SQLite header, QGIS treats the file as a
 *   plain database and shows no layers, with no error to explain why.
 * - **Header flags.** A flags byte claiming an envelope that is not there shifts the geometry by 32
 *   bytes and the reader fails on a valid file.
 *
 * Every one of those produces a file that looks finished and is wrong, which is the failure mode
 * this project keeps meeting. So the bytes are built here and asserted against the specification.
 *
 * Written against OGC 12-128r18 (GeoPackage 1.3).
 */
object GeoPackageFormat {

    /** `GPKG` as a big-endian int, written into the SQLite `application_id` header field. */
    const val APPLICATION_ID = 0x47504B47

    /** 10300 identifies GeoPackage 1.3, per the specification's `user_version` encoding. */
    const val USER_VERSION = 10_300

    const val SRS_WGS84 = 4326

    /**
     * A GeoPackage binary geometry for one WGS84 point.
     *
     * Layout, per the specification:
     * ```
     * byte[2]  magic  0x47 0x50   ("GP")
     * byte     version 0x00
     * byte     flags
     * int32    srs_id
     * byte[]   WKB geometry
     * ```
     * Flags bit 0 set means the header's integers are little-endian; bits 1..3 zero means no
     * envelope is present, which is legal and keeps the blob at 29 bytes for a point.
     *
     * No envelope is written deliberately: for a single point it would repeat the coordinates for
     * no benefit, and an envelope declared but miscomputed is worse than none.
     */
    fun pointBlob(lonDeg: Double, latDeg: Double, srsId: Int = SRS_WGS84): ByteArray {
        val out = ByteArray(HEADER_BYTES + WKB_POINT_BYTES)
        var i = 0

        out[i++] = 0x47 // 'G'
        out[i++] = 0x50 // 'P'
        out[i++] = 0x00 // version 0, meaning GeoPackage 1.x binary
        out[i++] = 0x01 // little-endian header, no envelope, not empty

        i = putIntLe(out, i, srsId)

        // WKB, with its own byte-order byte — the specification allows it to differ from the
        // header's, so it is stated explicitly rather than assumed to follow.
        out[i++] = 0x01 // little-endian WKB
        i = putIntLe(out, i, WKB_POINT)

        // X before Y. Longitude before latitude. This is the line that silently relocates a
        // survey to the Atlantic if it is transposed.
        i = putDoubleLe(out, i, lonDeg)
        putDoubleLe(out, i, latDeg)

        return out
    }

    /** The feature table holding the samples. */
    fun createFeatureTableSql(table: String): String =
        """
        CREATE TABLE "$table" (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            geom BLOB,
            seq INTEGER,
            timestamp_utc TEXT,
            rsrp_dbm INTEGER,
            sinr_db INTEGER,
            rsrq_db INTEGER,
            rssi_dbm INTEGER,
            cell_band TEXT,
            rat TEXT,
            serving_pci INTEGER,
            channel INTEGER,
            wifi_ssid TEXT,
            wifi_channel INTEGER,
            area TEXT,
            floor TEXT,
            gps_accuracy_m REAL,
            speed_mps REAL,
            level_bucket TEXT
        )
        """.trimIndent()

    /**
     * The three tables the specification requires before any feature table is valid.
     *
     * A file missing these is a SQLite database that happens to contain geometry, and a reader has
     * no way to discover the layer.
     */
    val REQUIRED_TABLES: List<String> = listOf(
        """
        CREATE TABLE gpkg_spatial_ref_sys (
            srs_name TEXT NOT NULL,
            srs_id INTEGER NOT NULL PRIMARY KEY,
            organization TEXT NOT NULL,
            organization_coordsys_id INTEGER NOT NULL,
            definition TEXT NOT NULL,
            description TEXT
        )
        """.trimIndent(),
        """
        CREATE TABLE gpkg_contents (
            table_name TEXT NOT NULL PRIMARY KEY,
            data_type TEXT NOT NULL,
            identifier TEXT UNIQUE,
            description TEXT DEFAULT '',
            last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE,
            srs_id INTEGER,
            CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id)
                REFERENCES gpkg_spatial_ref_sys(srs_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE gpkg_geometry_columns (
            table_name TEXT NOT NULL,
            column_name TEXT NOT NULL,
            geometry_type_name TEXT NOT NULL,
            srs_id INTEGER NOT NULL,
            z TINYINT NOT NULL,
            m TINYINT NOT NULL,
            CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name),
            CONSTRAINT fk_gc_tn FOREIGN KEY (table_name)
                REFERENCES gpkg_contents(table_name)
        )
        """.trimIndent(),
    )

    /**
     * The spatial reference rows the specification mandates.
     *
     * Two of the three describe nothing — "undefined cartesian" and "undefined geographic" — and
     * exist so that a table can legally reference an unknown system. A validator rejects the file
     * without them even though nothing uses them.
     */
    val REQUIRED_SRS_ROWS: List<Array<Any>> = listOf(
        arrayOf(
            "Undefined cartesian SRS", -1, "NONE", -1, "undefined",
            "undefined cartesian coordinate reference system",
        ),
        arrayOf(
            "Undefined geographic SRS", 0, "NONE", 0, "undefined",
            "undefined geographic coordinate reference system",
        ),
        arrayOf(
            "WGS 84 geodetic", SRS_WGS84, "EPSG", SRS_WGS84, WGS84_WKT,
            "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid",
        ),
    )

    // ---- byte helpers ----------------------------------------------------

    private fun putIntLe(out: ByteArray, at: Int, v: Int): Int {
        out[at] = (v and 0xFF).toByte()
        out[at + 1] = ((v ushr 8) and 0xFF).toByte()
        out[at + 2] = ((v ushr 16) and 0xFF).toByte()
        out[at + 3] = ((v ushr 24) and 0xFF).toByte()
        return at + 4
    }

    private fun putDoubleLe(out: ByteArray, at: Int, v: Double): Int {
        val bits = java.lang.Double.doubleToLongBits(v)
        for (b in 0 until 8) {
            out[at + b] = ((bits ushr (8 * b)) and 0xFF).toByte()
        }
        return at + 8
    }

    const val HEADER_BYTES = 8
    const val WKB_POINT_BYTES = 21
    private const val WKB_POINT = 1

    private const val WGS84_WKT =
        "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563," +
            "AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]]," +
            "PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]]," +
            "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]]," +
            "AUTHORITY[\"EPSG\",\"4326\"]]"
}
