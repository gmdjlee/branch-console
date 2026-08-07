package com.branchconsole.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals

/** `configs/indicators.yaml` `engine.preview_coverage_min` 실 SSOT 값 로드(M-09b, 0.80). */
class PreviewPolicyTest {
    @Test
    fun `previewCoverageMin reads engine preview_coverage_min from the real registry`() {
        assertEquals(0.80, PreviewPolicy.previewCoverageMin(RepoConfigSource), 1e-9)
    }
}
