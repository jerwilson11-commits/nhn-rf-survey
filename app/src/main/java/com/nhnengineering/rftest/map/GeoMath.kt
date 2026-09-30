package com.nhnengineering.rftest.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two points -- the one piece of geo math this app didn't already
 * have. [Mercator] is tile/pixel projection only; [RecordingState.distanceM][com.nhnengineering.rftest.service.RecordingState]
 * integrates reported GPS speed over time rather than differencing positions (see that class for
 * why position-differencing was abandoned for the session's own headline distance figure). Neither
 * is a lat/lon-to-metres function, and [GpsOutlierFilter] needs one.
 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. Exact enough at walking-survey scale; the extra cost over a
     *  flat-earth approximation is negligible and removes the question of how far is too far for
     *  the approximation to hold. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
