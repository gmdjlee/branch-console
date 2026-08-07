package com.branchconsole.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineInfoTest {
    @Test
    fun `module name is engine`() {
        assertEquals("engine", EngineInfo.MODULE_NAME)
    }
}
