package com.videostudio.app.upload

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Picking a posting time in the audience's (US) time zone. Pure logic so it can be tested. */
object Schedule {
    data class Zone(val label: String, val id: String)

    val ZONES = listOf(
        Zone("Eastern (New York)", "America/New_York"),
        Zone("Central (Chicago)", "America/Chicago"),
        Zone("Mountain (Denver)", "America/Denver"),
        Zone("Pacific (Los Angeles)", "America/Los_Angeles"),
    )

    /**
     * Times US Shorts audiences are most active (lunch, after school/work, evening). 7 PM Eastern
     * also reaches the West Coast at 4 PM.
     */
    val BEST_TIMES = listOf(9 * 60, 12 * 60, 15 * 60, 17 * 60, 19 * 60, 21 * 60)

    fun timeLabel(minutes: Int): String =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(LocalTime.of(minutes / 60, minutes % 60))

    /** The moment [dayOffset] days from today (in [zone]) at [minutes] after midnight, there. */
    fun at(zone: String, dayOffset: Int, minutes: Int, now: Long = System.currentTimeMillis()): Long {
        val z = ZoneId.of(zone)
        val day = Instant.ofEpochMilli(now).atZone(z).toLocalDate().plusDays(dayOffset.toLong())
        return ZonedDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60), z).toInstant().toEpochMilli()
    }

    /** First day offset (0 = today) on which [minutes] is still at least 20 minutes away. */
    fun firstDay(zone: String, minutes: Int, now: Long = System.currentTimeMillis()): Int =
        if (at(zone, 0, minutes, now) > now + 20 * 60_000) 0 else 1

    fun dayLabel(zone: String, offset: Int, now: Long = System.currentTimeMillis()): String = when (offset) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern("EEE d", Locale.US).format(Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).plusDays(offset.toLong()))
    }

    /** The same moment on this phone's clock, e.g. "Fri Nov 28, 5:30 AM". */
    fun onPhone(at: Long, phoneZone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US).format(Instant.ofEpochMilli(at).atZone(phoneZone))
}
