package com.nhnengineering.rftest.automation

/**
 * A scripted test sequence (roadmap E1) — an ordered, repeatable list of steps the recorder runs
 * hands-free during a session. Field tier, no root.
 *
 * ## Why this exists
 *
 * An acceptance survey is often not a walk but a set of fixed points: stand at each, let the radio
 * settle, take a throughput reading, move on. Done by hand that is a stopwatch in one hand and the
 * phone in the other, and the dwell times come out inconsistent — which matters, because a 5-second
 * dwell and a 30-second dwell at the same spot produce different medians. A script makes the dwell
 * identical at every point and frees the operator to just move and mark position.
 *
 * A [Step] is either a **dwell** (hold for N seconds, logging ordinary idle samples) or a
 * **throughput** burst (a download/upload measurement). [expand] flattens the plan — repeats and
 * all — into the exact ordered steps the runner executes, each carrying the waypoint label its
 * samples will be stamped with, so the per-area section of the report lines up with the plan.
 */
data class TestPlan(
    val name: String,
    val steps: List<Step>,
    /** How many times to run the whole list. 1 = once through. */
    val repeat: Int = 1,
) {
    enum class Kind { DWELL, THROUGHPUT }

    data class Step(
        val kind: Kind,
        /** Dwell length in seconds. Ignored for a throughput step (its length is the burst's own). */
        val seconds: Int = 0,
        /** Waypoint label stamped on the samples during this step. */
        val label: String? = null,
    )

    /** One step after repeats and labels are resolved — what the runner actually executes. */
    data class ExpandedStep(
        val kind: Kind,
        val seconds: Int,
        val label: String?,
        /** 1-based position across the whole expanded plan. */
        val index: Int,
        /** Which pass through [steps] this came from (1-based). */
        val cycle: Int,
    )

    val isValid: Boolean
        get() = name.isNotBlank() && repeat >= 1 && steps.isNotEmpty() &&
            steps.all { it.kind == Kind.THROUGHPUT || it.seconds > 0 }

    /** The flat, ordered list the runner walks. A label gets a "(run N)" suffix when repeat > 1. */
    fun expand(): List<ExpandedStep> {
        val out = ArrayList<ExpandedStep>(steps.size * repeat)
        var i = 0
        for (cycle in 1..repeat) {
            for (s in steps) {
                i++
                val label = s.label?.let { if (repeat > 1) "$it (run $cycle)" else it }
                out += ExpandedStep(s.kind, s.seconds, label, i, cycle)
            }
        }
        return out
    }

    /** Rough wall-clock estimate; a throughput burst is counted as [burstSeconds] (down+up). */
    fun estimatedSeconds(burstSeconds: Int = 10): Int =
        expand().sumOf { if (it.kind == Kind.THROUGHPUT) burstSeconds else it.seconds }

    val stepCount: Int get() = steps.size * repeat

    companion object {
        /**
         * The common case: [count] fixed points, each a [dwellSeconds] dwell and (optionally) a
         * throughput burst, optionally repeated. Points are labelled "Point 1".."Point N".
         */
        fun points(
            count: Int,
            dwellSeconds: Int,
            includeThroughput: Boolean,
            repeat: Int = 1,
            name: String = "$count-point plan",
        ): TestPlan {
            val steps = buildList {
                for (n in 1..count) {
                    val label = "Point $n"
                    add(Step(Kind.DWELL, dwellSeconds, label))
                    if (includeThroughput) add(Step(Kind.THROUGHPUT, 0, label))
                }
            }
            return TestPlan(name, steps, repeat)
        }
    }
}
