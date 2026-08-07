package com.branchconsole.app.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTransitionEvaluatorTest {
    @Test
    fun `no notification when the phase is unchanged`() {
        val decision = PhaseTransitionEvaluator.evaluate("GREEN", listOf("GREEN"))
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun `notifies when a single new tick changes the phase`() {
        val decision = PhaseTransitionEvaluator.evaluate("GREEN", listOf("AMBER"))
        assertTrue(decision.shouldNotify)
        assertEquals("GREEN", decision.fromPhase)
        assertEquals("AMBER", decision.toPhase)
        assertEquals(1, decision.batchSize)
    }

    @Test
    fun `a catchup batch compares only the phase before the batch to the final phase`() {
        // Transient AMBER -> ORANGE -> AMBER, but batch ends at AMBER, same as before the batch.
        val decision = PhaseTransitionEvaluator.evaluate("AMBER", listOf("ORANGE", "AMBER"))
        assertFalse("final phase equals the pre-batch phase -> no transition to report", decision.shouldNotify)
        assertEquals(2, decision.batchSize)
    }

    @Test
    fun `a catchup batch that ends in a different phase notifies once with batchSize greater than 1`() {
        val decision = PhaseTransitionEvaluator.evaluate("GREEN", listOf("AMBER", "ORANGE"))
        assertTrue(decision.shouldNotify)
        assertEquals("ORANGE", decision.toPhase)
        assertEquals(2, decision.batchSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty newPhases is rejected`() {
        PhaseTransitionEvaluator.evaluate("GREEN", emptyList())
    }
}
