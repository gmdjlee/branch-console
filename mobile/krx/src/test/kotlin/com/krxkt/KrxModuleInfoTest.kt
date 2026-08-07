package com.krxkt

import kotlin.test.Test
import kotlin.test.assertEquals

class KrxModuleInfoTest {
    @Test
    fun `module name is krx`() {
        assertEquals("krx", KrxModuleInfo.MODULE_NAME)
    }
}
