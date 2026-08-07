package com.branchconsole.app.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVerificationTest {
    @Test
    fun `success labels as confirmed`() {
        assertEquals("확인됨", VerifyResult.Success.label())
    }

    @Test
    fun `failure labels with the reason`() {
        assertEquals("실패: KEY_INVALID", VerifyResult.Failure("KEY_INVALID").label())
    }

    @Test
    fun `blank FRED key fails fast without a network call`() =
        runTest {
            val result = CredentialVerification.verifyFred("")
            assertTrue(result is VerifyResult.Failure)
        }

    @Test
    fun `blank KRX credentials fail fast without a network call`() =
        runTest {
            val result = CredentialVerification.verifyKrx("", "")
            assertTrue(result is VerifyResult.Failure)
        }
}
