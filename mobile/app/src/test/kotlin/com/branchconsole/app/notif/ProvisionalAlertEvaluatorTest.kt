package com.branchconsole.app.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionalAlertEvaluatorTest {
    @Test
    fun `suppressed coverage never notifies, even with a crit severity present`() {
        assertFalse(ProvisionalAlertEvaluator.shouldNotify(suppressed = true, severities = listOf(3)))
    }

    @Test
    fun `not suppressed but no crit severity does not notify`() {
        assertFalse(ProvisionalAlertEvaluator.shouldNotify(suppressed = false, severities = listOf(0, 1, 2, null)))
    }

    @Test
    fun `not suppressed with at least one crit severity notifies`() {
        assertTrue(ProvisionalAlertEvaluator.shouldNotify(suppressed = false, severities = listOf(1, 3, null)))
    }

    @Test
    fun `empty severities never notifies`() {
        assertFalse(ProvisionalAlertEvaluator.shouldNotify(suppressed = false, severities = emptyList()))
    }
}
