package com.branchconsole.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun `module name is branch-console`() {
        assertEquals("branch-console", AppInfo.MODULE_NAME)
    }
}
