package dev.oddhealth.app.ring

/**
 * When to ask the ring next with "Frequent updates" on.
 *
 * The ring takes a heart-rate reading every 9 min 45 s (measured on firmware
 * 2.3.2.0008) and reports when the latest was taken. Asking just after the next
 * one is due gets every reading with one request each; asking more often only
 * returns the same reading again.
 */
object ReadingSchedule {
    const val PERIOD_MS = 585_000L
    /** Wait this long after a reading is due, so it has been taken. */
    const val MARGIN_MS = 15_000L

    /**
     * Time (epoch ms) of the next request.
     * [lastReadingMs]: when the latest reading was taken, or null if unknown.
     * [lastPollMs]: when we last asked, or 0.
     */
    fun nextPoll(lastReadingMs: Long?, lastPollMs: Long, nowMs: Long): Long {
        if (lastReadingMs == null) return nowMs
        val expected = lastReadingMs + PERIOD_MS + MARGIN_MS
        // Asked since the reading was due and nothing new (ring off the finger,
        // or its timing shifted): try again one period after that request.
        if (lastPollMs >= expected) return lastPollMs + PERIOD_MS
        return maxOf(expected, nowMs)
    }
}
