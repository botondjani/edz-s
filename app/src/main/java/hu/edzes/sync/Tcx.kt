package hu.edzes.sync

import java.time.Instant

data class TrackPoint(val timeMs: Long, val lat: Double? = null, val lon: Double? = null, val altM: Double? = null, val hr: Int? = null)

/** GPS pontok és pulzusminták összefésülése: minden GPS pont a legutóbbi (max. 30 mp-es) pulzust kapja. */
fun mergePoints(route: List<TrackPoint>, hr: List<TrackPoint>): List<TrackPoint> {
    if (route.isEmpty()) return hr.sortedBy { it.timeMs }
    val sorted = hr.sortedBy { it.timeMs }
    var i = 0
    return route.sortedBy { it.timeMs }.map { p ->
        while (i + 1 < sorted.size && sorted[i + 1].timeMs <= p.timeMs) i++
        val s = sorted.getOrNull(i)?.takeIf { it.timeMs <= p.timeMs && p.timeMs - it.timeMs <= 30_000 }
        p.copy(hr = s?.hr)
    }
}

/** TCX fájl a Strava feltöltéshez. GPS nélküli edzésnél az ismert össztávot egyenletesen osztja el a pontokon. */
fun buildTcx(sport: String, startMs: Long, durationS: Int, distanceM: Double?, calories: Int?, points: List<TrackPoint>): String {
    val tcxSport = when (sport) { "run" -> "Running"; "bike" -> "Biking"; else -> "Other" }
    val start = Instant.ofEpochMilli(startMs)
    val spread = distanceM != null && points.none { it.lat != null }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
    sb.append("<Activities><Activity Sport=\"$tcxSport\"><Id>$start</Id>\n<Lap StartTime=\"$start\">")
    sb.append("<TotalTimeSeconds>$durationS</TotalTimeSeconds><DistanceMeters>${distanceM ?: 0.0}</DistanceMeters>")
    sb.append("<Calories>${calories ?: 0}</Calories><Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>\n<Track>\n")
    for (p in points) {
        sb.append("<Trackpoint><Time>${Instant.ofEpochMilli(p.timeMs)}</Time>")
        if (p.lat != null && p.lon != null)
            sb.append("<Position><LatitudeDegrees>${p.lat}</LatitudeDegrees><LongitudeDegrees>${p.lon}</LongitudeDegrees></Position>")
        p.altM?.let { sb.append("<AltitudeMeters>$it</AltitudeMeters>") }
        if (spread && durationS > 0) {
            val part = ((p.timeMs - startMs) / 1000.0 / durationS).coerceIn(0.0, 1.0)
            sb.append("<DistanceMeters>${distanceM!! * part}</DistanceMeters>")
        }
        p.hr?.let { sb.append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
        sb.append("</Trackpoint>\n")
    }
    sb.append("</Track></Lap></Activity></Activities></TrainingCenterDatabase>\n")
    return sb.toString()
}
