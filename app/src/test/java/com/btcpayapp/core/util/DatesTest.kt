package com.btcpayapp.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Dates reach the screen from two untrusted places: the server, which may send
 * any long, and the Material date picker, which speaks UTC midnight. The zone
 * tests run on both sides of UTC, because a conversion that is only right in
 * London is the bug these guard against.
 */
class DatesTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    /** What the picker hands back when 10 Oct 2026 is tapped, in any zone. */
    private val pickedOctober10 =
        LocalDate.of(2026, 10, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun local(epochSeconds: Long, zone: String): LocalDateTime =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of(zone)).toLocalDateTime()

    @Test
    fun `a picked day covers that local day, west and east of utc`() {
        for (zone in listOf("America/New_York", "Asia/Tokyo")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))

            val start = Dates.pickerStartOfDay(pickedOctober10)
            val end = Dates.pickerEndOfDay(pickedOctober10)

            assertEquals(zone, LocalDateTime.of(2026, 10, 10, 0, 0, 0), local(start, zone))
            assertEquals(zone, LocalDateTime.of(2026, 10, 10, 23, 59, 59), local(end, zone))
        }
    }

    @Test
    fun `a stored day opens the picker on the same day`() {
        for (zone in listOf("America/New_York", "Asia/Tokyo")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))

            assertEquals(zone, pickedOctober10, Dates.pickerMillis(Dates.pickerStartOfDay(pickedOctober10)))
            assertEquals(zone, pickedOctober10, Dates.pickerMillis(Dates.pickerEndOfDay(pickedOctober10)))
        }
    }

    @Test
    fun `the end of a day that daylight saving shortens is still 23 59 59`() {
        // 8 Mar 2026 has 23 hours in New York; "start plus 86 399 s" would land
        // on the 9th.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val picked = LocalDate.of(2026, 3, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2026, 3, 8, 23, 59, 59),
            local(Dates.pickerEndOfDay(picked), "America/New_York"),
        )
    }

    @Test
    fun `an absurd server timestamp is drawn, not thrown`() {
        // `Instant.ofEpochSecond(Long.MAX_VALUE)` throws, and the invoice list
        // formats `createdTime` in composition.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertTrue(Dates.date(Long.MAX_VALUE).contains("9999"))
        Dates.full(Long.MIN_VALUE)
        Dates.time(Long.MAX_VALUE)
        Dates.relative(Long.MAX_VALUE, nowSeconds = 1_710_000_000L)
        Dates.relative(Long.MIN_VALUE, nowSeconds = 1_710_000_000L)
        Dates.pickerMillis(Long.MAX_VALUE)
    }

    @Test
    fun `relative time reads naturally`() {
        val now = 1_710_000_000L
        assertEquals("just now", Dates.relative(now - 10, now))
        assertEquals("5 min ago", Dates.relative(now - 300, now))
        assertEquals("2 h ago", Dates.relative(now - 7_200, now))
        assertEquals("3 d ago", Dates.relative(now - 3 * 86_400, now))
        assertEquals("in 5 min", Dates.relative(now + 300, now))
    }

    @Test
    fun `countdown is null once expired`() {
        val now = 1_710_000_000L
        assertEquals(null, Dates.countdown(now - 1, now))
        assertEquals("5:00", Dates.countdown(now + 300, now))
        assertEquals("1:00:00", Dates.countdown(now + 3600, now))
    }
}
