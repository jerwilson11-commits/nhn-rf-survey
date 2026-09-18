package com.nhnengineering.rftest.report

/**
 * Checks an operator's band-lock declaration against the bands the session actually saw.
 *
 * ## Why a declaration needs checking at all
 *
 * Nothing in this app performs a band lock; it is done externally, in the handset's RF toolkit or a
 * diagnostic tool. So the recorded lock is a claim by the operator, and claims made at the start of
 * a walk are exactly the ones that go stale — the lock was set on the other handset, or it was
 * cleared by a reboot, or it was set to n41 and the box said n40.
 *
 * The bands observed during the walk are a measurement, and they can contradict the claim. That is
 * worth saying, because the two failure directions have opposite consequences:
 *
 * - **Bands appeared that the lock excludes.** The lock was not in force. Every statistic in the
 *   report is then a free-running walk mislabelled as a locked one, which is the worse error: a
 *   reader would take the numbers as band-specific when they are not.
 * - **The locked band never appeared.** Either the lock named a band this site does not carry, or
 *   it was set wrong. The survey may contain nothing of what it set out to measure.
 *
 * Silence when the two agree. A confirmation on every report would train the reader to skip it.
 */
object BandLockCheck {

    enum class Severity { INFO, CHECK }

    data class Finding(val headline: String, val detail: String, val severity: Severity)

    /**
     * @param declared bands the operator recorded as locked, e.g. `["n41"]`.
     * @param observed band labels seen in the session. These may be ambiguous — the app writes
     *   "n2/n25" where a channel belongs to either — so a declaration matching any alternative
     *   counts as a match rather than a contradiction.
     */
    fun check(declared: List<String>, observed: List<String>): List<Finding> {
        val locks = declared.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (locks.isEmpty()) return emptyList()

        val seen = observed.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (seen.isEmpty()) return emptyList()

        val unexpected = seen.filterNot { band -> locks.any { lock -> matches(lock, band) } }
        val missing = locks.filterNot { lock -> seen.any { band -> matches(lock, band) } }

        val findings = mutableListOf<Finding>()

        if (unexpected.isNotEmpty()) {
            findings += Finding(
                "Bands were seen that the declared lock excludes",
                "The session was recorded as locked to ${locks.joinToString(", ")}, but " +
                    "${unexpected.joinToString(", ")} also appeared. The lock was probably not in " +
                    "force. Statistics in this report should be read as a free-running walk, not " +
                    "as measurements of the locked band.",
                Severity.CHECK,
            )
        }

        if (missing.isNotEmpty()) {
            findings += Finding(
                "A locked band was never seen",
                "The session was recorded as locked to ${missing.joinToString(", ")}, which does " +
                    "not appear anywhere in the data. Either the site does not carry it, or the " +
                    "lock named a different band from the one intended.",
                Severity.CHECK,
            )
        }

        return findings
    }

    /**
     * True when a declared band and an observed label refer to the same band.
     *
     * The observed side may be ambiguous — "n2/n25" is written where the channel is valid in both
     * and the modem did not say which. Treating that as a mismatch would raise a finding on every
     * locked walk across an overlapping allocation, so any alternative matching is enough.
     */
    private fun matches(declared: String, observed: String): Boolean =
        observed.split('/', ',').any { it.trim().equals(declared, ignoreCase = true) }
}
