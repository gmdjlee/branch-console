package com.branchconsole.app.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** D-23 §23.2 수치 예(66.7/45.2/67.7) 재현과 동일 표기 규칙 — 분수식 입력, 리터럴 비교. */
class PercentFormatTest {
    @Test
    fun `21 over 31 rounds half-up to 67 point 7 percent`() {
        assertEquals("67.7%", formatCoveragePercent(21.0 / 31.0))
    }

    @Test
    fun `29 point 5 over 31 rounds to 95 point 2 percent`() {
        assertEquals("95.2%", formatCoveragePercent(29.5 / 31.0))
    }

    @Test
    fun `composite null formats as N over A`() {
        assertEquals("N/A", formatComposite(null))
    }

    @Test
    fun `composite value rounds half-up to one decimal`() {
        assertEquals("24.3", formatComposite(24.25))
    }
}
