package dev.oddhealth.app

import dev.oddhealth.app.ring.ReadingSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingScheduleTest {
    private val period = ReadingSchedule.PERIOD_MS
    private val margin = ReadingSchedule.MARGIN_MS

    @Test
    fun asksNowWhenTheLatestReadingIsUnknown() {
        assertEquals(1_000L, ReadingSchedule.nextPoll(null, 0, 1_000))
    }

    @Test
    fun asksJustAfterTheNextReadingIsDue() {
        // Reading at t=0, now 2 minutes later: next reading due at 9:45, ask at 10:00.
        assertEquals(period + margin, ReadingSchedule.nextPoll(0, 0, 120_000))
    }

    @Test
    fun asksImmediatelyIfTheDueTimeWasMissed() {
        // Phone asleep: now is well past the due time and we haven't asked since.
        assertEquals(900_000L, ReadingSchedule.nextPoll(0, 300_000, 900_000))
    }

    @Test
    fun backsOffWhenNoNewReadingArrives() {
        // Asked at 10:00 and the reading is still the one from 0:00 (ring off the finger).
        val askedAt = period + margin
        assertEquals(askedAt + period, ReadingSchedule.nextPoll(0, askedAt, askedAt + 1_000))
    }

    @Test
    fun matchesTheMeasuredCadence() {
        // Readings seen at 08:10:37, 08:20:22, 08:30:16, 08:40:01, 08:49:46: 585-594 s apart.
        val readings = listOf(0L, 585_000L, 1_179_000L, 1_764_000L, 2_349_000L)
        for ((prev, next) in readings.zipWithNext()) {
            val ask = ReadingSchedule.nextPoll(prev, 0, prev)
            check(ask >= next) { "asked at $ask, before reading at $next" }
            check(ask - next < 30_000) { "asked ${ask - next} ms after reading at $next" }
        }
    }
}
