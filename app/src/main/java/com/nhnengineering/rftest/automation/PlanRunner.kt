package com.nhnengineering.rftest.automation

/**
 * Executes a [TestPlan] against a set of host actions. Kept free of Android and of the recording
 * service so the sequencing logic — order, labelling, progress, completion — is unit-testable with a
 * fake [Actions]; the real implementation (dwell via delay, throughput via a burst, labels via the
 * recording state) lives in the service.
 */
object PlanRunner {

    /** Live progress, published after each step begins. */
    data class Progress(
        val planName: String,
        /** 1-based index of the step now running. */
        val index: Int,
        val total: Int,
        val kind: TestPlan.Kind,
        val label: String?,
    ) {
        val done: Boolean get() = index >= total
    }

    interface Actions {
        /** Stamp subsequent samples with this waypoint label (null clears it). */
        fun label(label: String?)

        /** Publish progress for the UI. */
        fun progress(progress: Progress)

        /** Hold for [seconds], logging ordinary samples. Must be cancellable. */
        suspend fun dwell(seconds: Int)

        /** Run one throughput burst and return when it finishes. */
        suspend fun throughput()

        /** True while the run should continue; false asks it to stop at the next boundary. */
        fun keepRunning(): Boolean
    }

    /**
     * Runs [plan] to completion or until [Actions.keepRunning] turns false. Returns the number of
     * steps actually executed. The label is cleared on the way out so a stopped or finished plan
     * does not leave every later free-walk sample tagged with the last point.
     */
    suspend fun run(plan: TestPlan, actions: Actions): Int {
        val steps = plan.expand()
        var executed = 0
        try {
            for (step in steps) {
                if (!actions.keepRunning()) break
                actions.label(step.label)
                actions.progress(Progress(plan.name, step.index, steps.size, step.kind, step.label))
                when (step.kind) {
                    TestPlan.Kind.DWELL -> actions.dwell(step.seconds)
                    TestPlan.Kind.THROUGHPUT -> actions.throughput()
                }
                executed++
            }
        } finally {
            actions.label(null)
        }
        return executed
    }
}
