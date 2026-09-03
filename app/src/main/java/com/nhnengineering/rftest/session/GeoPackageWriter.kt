package com.nhnengineering.rftest.session

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Writes a session as a GeoPackage.
 *
 * ## Why bother, given KML and GeoJSON already exist
 *
 * GeoPackage is what the people this app competes with already use. Network Survey — the strongest
 * free tool in this space — logs to GeoPackage, and anyone doing geospatial analysis opens one in
 * QGIS without thinking about it. Offering it costs little and removes a reason to prefer something
 * else.
 *
 * It is also a better container than either alternative for this data. KML is a display format and
 * loses typed attributes; GeoJSON is text and grows large. A GeoPackage is a SQLite database, so
 * every sample's attributes stay typed and queryable — an analyst can ask for every point below
 * −105 dBm on the third floor in SQL, which is exactly the question a survey exists to answer.
 *
 * ## The split
 *
 * This class holds only the database calls. Everything error-prone — the geometry bytes, the
 * mandatory tables, the spatial reference rows — lives in [GeoPackageFormat] and is unit-tested
 * there, because `android.database.sqlite` is a stub in JVM tests and anything written here cannot
 * be. The division is deliberate: the parts of this format that fail silently are all in the part
 * that can be tested.
 */
object GeoPackageWriter {

    const val TABLE = "samples"

    /**
     * Writes [points] into a new GeoPackage at [out].
     *
     * Only GPS-located samples are written, because a feature without geometry is not a feature —
     * an indoor sample placed on a floorplan has no geographic position to give. The count written
     * is returned so the caller can tell the operator how many were carried, rather than implying
     * the file holds the whole session.
     */
    suspend fun write(
        summary: SessionSummary,
        points: List<TrackPoint>,
        out: File,
    ): Int = withContext(Dispatchers.IO) {
        // A stale file would be opened and appended to, producing a database with two sets of
        // mandatory rows and a duplicate contents entry.
        if (out.exists()) out.delete()

        // The rollback journal has to go with it. Deleting the database but leaving a journal from
        // a previous write means the next open finds what looks like a hot journal beside a new
        // database and tries to roll it back into pages that have nothing to do with it. Left over
        // from an interrupted export this is the one way this writer could produce a corrupt file
        // rather than no file.
        val journal = File(out.parentFile, out.name + "-journal")
        if (journal.exists()) journal.delete()

        val located = points.filter { it.hasGpsPosition }
        val db = SQLiteDatabase.openOrCreateDatabase(out, null)
        try {
            db.beginTransaction()

            // The SQLite header field that identifies this as a GeoPackage. Without it a reader
            // sees an ordinary database and offers no layers, with nothing to explain why.
            db.execSQL("PRAGMA application_id = ${GeoPackageFormat.APPLICATION_ID}")
            db.execSQL("PRAGMA user_version = ${GeoPackageFormat.USER_VERSION}")

            GeoPackageFormat.REQUIRED_TABLES.forEach { db.execSQL(it) }

            for (row in GeoPackageFormat.REQUIRED_SRS_ROWS) {
                db.execSQL(
                    "INSERT INTO gpkg_spatial_ref_sys " +
                        "(srs_name, srs_id, organization, organization_coordsys_id, definition, " +
                        "description) VALUES (?, ?, ?, ?, ?, ?)",
                    row,
                )
            }

            db.execSQL(GeoPackageFormat.createFeatureTableSql(TABLE))

            // The bounding box goes in gpkg_contents. Computed from what is actually written
            // rather than from the session summary, which includes samples this file does not
            // carry — a declared extent larger than the data makes a reader zoom to empty space.
            val minLat = located.minOfOrNull { it.latitudeDeg!! }
            val maxLat = located.maxOfOrNull { it.latitudeDeg!! }
            val minLon = located.minOfOrNull { it.longitudeDeg!! }
            val maxLon = located.maxOfOrNull { it.longitudeDeg!! }

            db.execSQL(
                "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, " +
                    "min_x, min_y, max_x, max_y, srs_id) VALUES (?, 'features', ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    TABLE,
                    summary.displayName,
                    "RF survey samples — ${located.size} GPS-located points",
                    minLon, minLat, maxLon, maxLat,
                    GeoPackageFormat.SRS_WGS84,
                ),
            )

            db.execSQL(
                "INSERT INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, " +
                    "srs_id, z, m) VALUES (?, 'geom', 'POINT', ?, 0, 0)",
                arrayOf(TABLE, GeoPackageFormat.SRS_WGS84),
            )

            for (p in located) {
                val v = ContentValues()
                v.put("geom", GeoPackageFormat.pointBlob(p.longitudeDeg!!, p.latitudeDeg!!))
                v.put("seq", p.sequence)
                v.put(
                    "timestamp_utc",
                    if (p.timestampUtcMillis > 0) {
                        Instant.ofEpochMilli(p.timestampUtcMillis).toString()
                    } else {
                        null
                    },
                )
                v.put("rsrp_dbm", p.rsrpDbm)
                v.put("sinr_db", p.sinrDb)
                v.put("rsrq_db", p.rsrqDb)
                v.put("rssi_dbm", p.rssiDbm)
                v.put("cell_band", p.cellBand)
                v.put("rat", p.rat)
                v.put("serving_pci", p.servingPci)
                v.put("channel", p.cells.firstOrNull { it.serving }?.channel)
                v.put("wifi_ssid", p.ssid)
                v.put("wifi_channel", p.channel)
                v.put("area", p.waypoint)
                v.put("floor", p.floor)
                v.put("gps_accuracy_m", p.accuracyM)
                v.put("speed_mps", p.speedMps)
                // The same bucket the map, the report and the live view use, carried into the file
                // so a QGIS style built on it matches every other surface.
                v.put(
                    "level_bucket",
                    RsrpBucket.of(p.rsrpDbm)?.label ?: RssiBucket.of(p.rssiDbm)?.label,
                )
                db.insert(TABLE, null, v)
            }

            db.setTransactionSuccessful()
            db.endTransaction()
        } finally {
            runCatching { db.close() }
        }

        // SQLiteDatabase creates its file owner-only (0600), unlike an ordinary file write, which
        // inherits the directory's group. Verified on device: the exported .gpkg came out
        // -rw------- and could not be read by adb, by run-as, or by anything except this process.
        // Sharing still worked, because the app reads its own file -- but a survey file that
        // cannot be copied off the handset is most of the way to useless, and the failure is
        // silent. The directory is app-scoped already, so relaxing the mode gives nothing away.
        runCatching {
            out.setReadable(true, false)
            out.setWritable(true, true)
        }

        // Android leaves a zero-length journal behind after a clean close. It is harmless -- SQLite
        // treats an empty journal as no journal -- but it sits in the folder the operator browses
        // to find the export, and an unexplained file next to a deliverable invites a support
        // question. Removing it also means the guard above has nothing to find next time.
        runCatching { if (journal.exists()) journal.delete() }

        located.size
    }
}
