package com.kadir.linkdnm

/**
 * An energy-change detector cannot prove the exact first vocal syllable.
 * Keep the source uncut by default; any estimated start requires user approval.
 */
object IntroSelection {
    const val PRE_ROLL_MS = 1_800L
    const val STEP_MS = 100L
    const val MAX_SELECT_MS = 90_000L

    fun maxStart(durationMs: Long): Long =
        if (durationMs <= 1500L) 0L else minOf(MAX_SELECT_MS, durationMs - 1500L)

    fun clamp(positionMs: Long, durationMs: Long): Long =
        positionMs.coerceIn(0L, maxStart(durationMs))

    /** Safe proposal, not an automatic cut. Includes a short lead-in before detection. */
    fun proposal(rawEstimateMs: Long, durationMs: Long): Long? {
        if (rawEstimateMs < 3000L || rawEstimateMs > 38_000L) return null
        val start = clamp(rawEstimateMs - PRE_ROLL_MS, durationMs)
        // Keep the slider, preview and actual exported audio at the same 0.5 s position.
        val snapped = (start / STEP_MS) * STEP_MS
        return snapped.takeIf { it > 0L }
    }

    fun offset(currentMs: Long, changeMs: Long, durationMs: Long): Long =
        clamp(currentMs + changeMs, durationMs)

    fun label(timeMs: Long): String {
        val tenths = (timeMs.coerceAtLeast(0L) / STEP_MS).toInt()
        val seconds = tenths / 10
        return "%02d:%02d.%d".format(java.util.Locale.ROOT,
            seconds / 60, seconds % 60, tenths % 10)
    }
}
