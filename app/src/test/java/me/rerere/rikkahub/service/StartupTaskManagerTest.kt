package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.StartupTask
import me.rerere.rikkahub.data.datastore.StartupTaskTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StartupTaskManagerTest {
    @Test
    fun `disabled or blank task never auto runs`() {
        val date = LocalDate.of(2026, 11, 28)
        val disabled = StartupTask(id = "1", name = "A", command = "echo hi", enabled = false)
        val blank = StartupTask(id = "2", name = "B", command = "   ", enabled = true)

        assertFalse(shouldAutoRunStartupTask(disabled, null, date, isColdStart = true))
        assertFalse(shouldAutoRunStartupTask(blank, null, date, isColdStart = true))
    }

    @Test
    fun `every launch runs on cold start only`() {
        val date = LocalDate.of(2026, 11, 28)
        val task = StartupTask(id = "1", name = "MCP", command = "mcp", trigger = StartupTaskTrigger.EVERY_LAUNCH)
        val record = StartupTaskScheduleRecord(
            lastAutoRunAt = 1000L,
            lastAutoRunDate = date.toString(),
            lastAutoRunWeek = date.isoWeekKey(),
        )

        assertTrue(shouldAutoRunStartupTask(task, record, date, isColdStart = true))
        assertFalse(shouldAutoRunStartupTask(task, record, date, isColdStart = false))
    }

    @Test
    fun `daily first launch runs once per calendar day`() {
        val day1 = LocalDate.of(2026, 11, 28)
        val day2 = LocalDate.of(2026, 11, 29)
        val task = StartupTask(
            id = "1",
            name = "Daily",
            command = "sync",
            trigger = StartupTaskTrigger.DAILY_FIRST_LAUNCH,
        )

        assertTrue(shouldAutoRunStartupTask(task, null, day1, isColdStart = true))
        val ranDay1 = StartupTaskScheduleRecord(lastAutoRunAt = 1L, lastAutoRunDate = day1.toString())
        assertFalse(shouldAutoRunStartupTask(task, ranDay1, day1, isColdStart = true))
        assertFalse(shouldAutoRunStartupTask(task, ranDay1, day1, isColdStart = false))
        assertTrue(shouldAutoRunStartupTask(task, ranDay1, day2, isColdStart = true))
        assertTrue(shouldAutoRunStartupTask(task, ranDay1, day2, isColdStart = false))
    }

    @Test
    fun `weekly first launch deduplicates within same ISO week`() {
        val saturday = LocalDate.of(2026, 11, 28) // 2026-W48
        val sunday = LocalDate.of(2026, 11, 29) // 2026-W48
        val monday = LocalDate.of(2026, 11, 30) // 2026-W49
        val task = StartupTask(
            id = "1",
            name = "Weekly",
            command = "apt update",
            trigger = StartupTaskTrigger.WEEKLY_FIRST_LAUNCH,
        )

        assertEquals("2026-W48", saturday.isoWeekKey())
        assertEquals("2026-W48", sunday.isoWeekKey())
        assertEquals("2026-W49", monday.isoWeekKey())

        val ranSat = StartupTaskScheduleRecord(lastAutoRunAt = 1L, lastAutoRunWeek = saturday.isoWeekKey())
        assertFalse(shouldAutoRunStartupTask(task, ranSat, sunday, isColdStart = true))
        assertTrue(shouldAutoRunStartupTask(task, ranSat, monday, isColdStart = true))
        assertTrue(shouldAutoRunStartupTask(task, ranSat, monday, isColdStart = false))
    }

    @Test
    fun `once task runs only before first auto run on cold start`() {
        val date = LocalDate.of(2026, 11, 28)
        val task = StartupTask(id = "1", name = "Init", command = "init", trigger = StartupTaskTrigger.ONCE)

        assertTrue(shouldAutoRunStartupTask(task, null, date, isColdStart = true))
        assertFalse(shouldAutoRunStartupTask(task, null, date, isColdStart = false))
        val ran = StartupTaskScheduleRecord(lastAutoRunAt = 1234L)
        assertFalse(shouldAutoRunStartupTask(task, ran, date.plusDays(1), isColdStart = true))
    }
}
