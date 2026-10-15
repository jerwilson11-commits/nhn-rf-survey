package com.nhnengineering.rftest.automation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPlanTest {

    @Test
    fun `points template builds dwell plus throughput per point`() {
        val plan = TestPlan.points(count = 3, dwellSeconds = 20, includeThroughput = true)
        assertTrue(plan.isValid)
        // 3 points x (dwell + throughput) = 6 steps.
        assertEquals(6, plan.steps.size)
        val expanded = plan.expand()
        assertEquals(6, expanded.size)
        assertEquals(TestPlan.Kind.DWELL, expanded[0].kind)
        assertEquals(TestPlan.Kind.THROUGHPUT, expanded[1].kind)
        assertEquals("Point 1", expanded[0].label)
        assertEquals("Point 2", expanded[2].label)
        // Estimate: 3 dwells x20s + 3 bursts x10s = 90s.
        assertEquals(90, plan.estimatedSeconds(burstSeconds = 10))
    }

    @Test
    fun `dwell-only points omit throughput`() {
        val plan = TestPlan.points(count = 4, dwellSeconds = 15, includeThroughput = false)
        assertEquals(4, plan.steps.size)
        assertTrue(plan.expand().all { it.kind == TestPlan.Kind.DWELL })
        assertEquals(60, plan.estimatedSeconds())
    }

    @Test
    fun `repeat multiplies steps and tags the run number`() {
        val plan = TestPlan.points(count = 2, dwellSeconds = 10, includeThroughput = false, repeat = 3)
        val expanded = plan.expand()
        assertEquals(6, expanded.size)
        assertEquals(6, plan.stepCount)
        assertEquals("Point 1 (run 1)", expanded[0].label)
        assertEquals("Point 1 (run 2)", expanded[2].label)
        assertEquals("Point 2 (run 3)", expanded[5].label)
        // Indices are 1-based and contiguous across cycles.
        assertEquals(listOf(1, 2, 3, 4, 5, 6), expanded.map { it.index })
        assertEquals(listOf(1, 1, 2, 2, 3, 3), expanded.map { it.cycle })
    }

    @Test
    fun `validation rejects empty, zero-dwell and bad repeat`() {
        assertFalse(TestPlan("x", emptyList()).isValid)
        assertFalse(TestPlan("x", listOf(TestPlan.Step(TestPlan.Kind.DWELL, 0, "P1"))).isValid)
        assertFalse(TestPlan("", listOf(TestPlan.Step(TestPlan.Kind.DWELL, 5))).isValid)
        assertFalse(TestPlan("x", listOf(TestPlan.Step(TestPlan.Kind.DWELL, 5)), repeat = 0).isValid)
        // A throughput step needs no dwell seconds.
        assertTrue(TestPlan("x", listOf(TestPlan.Step(TestPlan.Kind.THROUGHPUT))).isValid)
    }

    private class FakeActions(var stopAfter: Int = Int.MAX_VALUE) : PlanRunner.Actions {
        val log = mutableListOf<String>()
        val progress = mutableListOf<PlanRunner.Progress>()
        var labelNow: String? = "sentinel"
        private var started = 0
        override fun label(label: String?) { labelNow = label; log += "label=$label" }
        override fun progress(progress: PlanRunner.Progress) { this.progress += progress }
        override suspend fun dwell(seconds: Int) { log += "dwell:$seconds" }
        override suspend fun throughput() { log += "throughput" }
        override fun keepRunning(): Boolean { return started++ < stopAfter }
    }

    @Test
    fun `runner executes steps in order and clears the label at the end`() = runBlocking {
        val plan = TestPlan.points(count = 2, dwellSeconds = 10, includeThroughput = true)
        val actions = FakeActions()
        val executed = PlanRunner.run(plan, actions)

        assertEquals(4, executed)
        assertEquals(
            listOf("label=Point 1", "dwell:10", "label=Point 1", "throughput",
                "label=Point 2", "dwell:10", "label=Point 2", "throughput", "label=null"),
            actions.log,
        )
        // Label is cleared so later free-walk samples are not tagged with the last point.
        assertEquals(null, actions.labelNow)
        assertEquals(4, actions.progress.size)
        assertTrue(actions.progress.last().done)
    }

    @Test
    fun `runner stops early when asked and still clears the label`() = runBlocking {
        val plan = TestPlan.points(count = 5, dwellSeconds = 10, includeThroughput = false)
        val actions = FakeActions(stopAfter = 2)
        val executed = PlanRunner.run(plan, actions)

        assertEquals(2, executed)
        assertEquals(null, actions.labelNow)
    }
}
