package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class AlarmHashFormatTest {
    @Test
    fun `formats hash with zero-padded time and ALL when no days`() {
        assertEquals("06:05:ALL:true", alarmHash(6, 5, emptySet(), true))
    }

    @Test
    fun `formats hash with uppercase 3-letter sorted day names`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals("09:30:MON,FRI,SUN:false", alarmHash(9, 30, days, false))
    }
}
