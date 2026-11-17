package dev.oddhealth.core.model

/**
 * Personal skin-temperature baseline: the median of up to [NIGHTS] earlier
 * nights. Health Connect stores skin temperature as a change from a baseline,
 * and so does our UI.
 */
object SkinBaseline {
    const val NIGHTS = 30
    const val MIN_NIGHTS = 3

    /** Baseline for [night], from nights that ended before it; null until there are [MIN_NIGHTS]. */
    fun forNight(night: SkinTemperature, history: List<SkinTemperature>): Double? {
        val earlier = history
            .filter { it.end < night.start && it.celsius > 0 }
            .sortedByDescending { it.end }
            .take(NIGHTS)
            .map { it.celsius }
            .sorted()
        if (earlier.size < MIN_NIGHTS) return null
        val mid = earlier.size / 2
        return if (earlier.size % 2 == 1) earlier[mid] else (earlier[mid - 1] + earlier[mid]) / 2
    }
}
