package com.branchconsole.app.collectors.krx

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K-03 간격 로직을 가상 시계(fake clock/sleep)로 검증한다 — 실 대기 없이 빠르게 실행된다.
 */
class KrxRateLimiterTest {
    @Test
    fun `first call never sleeps`() =
        runTest {
            val sleeps = mutableListOf<Long>()
            val limiter = KrxRateLimiter(minIntervalMs = 1000, clock = { 0L }, sleep = { sleeps += it })

            limiter.throttle()

            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `call within interval sleeps for the remainder`() =
        runTest {
            val sleeps = mutableListOf<Long>()
            var now = 0L
            val limiter = KrxRateLimiter(minIntervalMs = 1000, clock = { now }, sleep = { sleeps += it })

            limiter.throttle()
            now = 300
            limiter.throttle()

            assertEquals(listOf(700L), sleeps)
        }

    @Test
    fun `call after interval elapsed does not sleep`() =
        runTest {
            val sleeps = mutableListOf<Long>()
            var now = 0L
            val limiter = KrxRateLimiter(minIntervalMs = 1000, clock = { now }, sleep = { sleeps += it })

            limiter.throttle()
            now = 1500
            limiter.throttle()

            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `three calls at fixed time sleep on the second and third`() =
        runTest {
            val sleeps = mutableListOf<Long>()
            val limiter = KrxRateLimiter(minIntervalMs = 1000, clock = { 0L }, sleep = { sleeps += it })

            repeat(3) { limiter.throttle() }

            assertEquals(listOf(1000L, 1000L), sleeps)
        }
}
