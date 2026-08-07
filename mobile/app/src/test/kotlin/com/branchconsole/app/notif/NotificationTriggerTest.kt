package com.branchconsole.app.notif

import androidx.test.core.app.ApplicationProvider
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private const val REGISTRY_VERSION = "0.0.0-test"

/** 2국면(GREEN/AMBER) 축소 statemachine — sustain/dwell/cooldown 전부 1 또는 0으로 둬 "틱 1개 =
 * 전이 1개"가 되게 만든다(전이 판정 자체가 이 테스트의 관심사이지 히스테리시스 세부가 아니다). */
private val STATEMACHINE_YAML =
    """
    schema: statemachine/1
    phases: [GREEN, AMBER]
    initial_phase: GREEN
    upgrade:
      rules:
        AMBER: { composite_gte: 20 }
    downgrade:
      rules:
        exit_AMBER: { composite_lt: 10 }
    skip_levels: true
    profiles:
      mobile_daily:
        tick: 1d
        promote_sustain_ticks: 1
        demote_below_ticks: 1
        min_dwell_ticks: 1
        reentry_cooldown_ticks: 0
    """.trimIndent()

private class FixtureConfigSource(private val doc: String) : ConfigSource {
    override fun open(name: String): InputStream = doc.byteInputStream()
}

private val FIXTURE = FixtureConfigSource(STATEMACHINE_YAML)

private fun tickInputRow(
    date: String,
    composite: Double?,
    isCatchup: Boolean = false,
) = TickInputEntity(
    tradingDate = date,
    composite = composite,
    distinctAxes = 0,
    anyCrit = false,
    anyExtreme = false,
    severitiesJson = "{}",
    coverage = 1.0,
    registryVersion = REGISTRY_VERSION,
    gapReason = null,
    frozenAt = 0L,
    firedAxes = null,
    visibleAtByIndicator = null,
    isCatchup = isCatchup,
    warmupStatusJson = null,
    pitQuality = "live",
)

private fun failedRunLog(
    date: String,
    ranAt: Long,
    detail: String,
) = RunLogEntity(tradingDate = date, ranAt = ranAt, status = "failed", detail = detail)

/**
 * TASK MT1-08 완료 기준 — 노티 트리거 테스트(`--tests "*NotificationTrigger*"`, 억제 상태
 * 미발신·전이 없을 때 미발신 포함). [ProvisionalAlertEvaluatorTest]/[PhaseTransitionEvaluatorTest]
 * 가 순수 판정 로직을 단위 검증하고, 이 파일은 [NotificationSync]가 실제 Room 테이블을 읽어
 * 그 판정을 옳게 조립·중복 억제하는지(cursor 왕복 포함) Robolectric으로 통합 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationTriggerTest {
    private lateinit var db: LakeDatabase
    private val sent = mutableListOf<Sent>()

    private data class Sent(val channelId: String, val notificationId: Int, val title: String, val text: String)

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        sent.clear()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sync(clock: Clock = Clock.systemUTC()): NotificationSync =
        NotificationSync(
            context = ApplicationProvider.getApplicationContext(),
            tickInputDao = db.tickInputDao(),
            runLogDao = db.runLogDao(),
            configSource = FIXTURE,
            clock = clock,
            notify = { channelId, id, title, text -> sent += Sent(channelId, id, title, text) },
        )

    // ------------------------------------------------------------------ phase_transition

    @Test
    fun `no tick_input rows means no phase transition notification`() =
        runTest {
            sync().checkAndNotify()
            val phaseTransitionsSent = sent.none { it.channelId == NotificationChannels.PHASE_TRANSITION }
            assertTrue("no data yet -> nothing to notify", phaseTransitionsSent)
        }

    @Test
    fun `same phase across ticks does not notify (no transition)`() =
        runTest {
            db.tickInputDao().insert(tickInputRow("2026-08-04", composite = 0.0)) // GREEN
            db.tickInputDao().insert(tickInputRow("2026-08-05", composite = 5.0)) // still GREEN

            sync().checkAndNotify()

            assertTrue(
                "composite stayed below the AMBER threshold the whole time -> no transition",
                sent.none { it.channelId == NotificationChannels.PHASE_TRANSITION },
            )
        }

    @Test
    fun `a single new tick that crosses the phase threshold notifies exactly once`() =
        runTest {
            db.tickInputDao().insert(tickInputRow("2026-08-04", composite = 0.0)) // GREEN
            db.tickInputDao().insert(tickInputRow("2026-08-05", composite = 50.0)) // -> AMBER

            sync().checkAndNotify()

            val phaseNotifs = sent.filter { it.channelId == NotificationChannels.PHASE_TRANSITION }
            assertEquals(1, phaseNotifs.size)
            assertTrue(phaseNotifs.single().text.contains("GREEN"))
            assertTrue(phaseNotifs.single().text.contains("AMBER"))
        }

    @Test
    fun `a re-check with no new rows does not re-notify the same transition`() =
        runTest {
            db.tickInputDao().insert(tickInputRow("2026-08-04", composite = 0.0))
            db.tickInputDao().insert(tickInputRow("2026-08-05", composite = 50.0))
            val notifier = sync()
            notifier.checkAndNotify()
            sent.clear()

            notifier.checkAndNotify() // same rows, cursor already advanced

            assertTrue("cursor already covers this row -> no duplicate", sent.isEmpty())
        }

    @Test
    fun `a catchup batch of several new ticks notifies at most once (no per-tick spam)`() =
        runTest {
            db.tickInputDao().insert(tickInputRow("2026-08-03", composite = 0.0)) // GREEN (already notified baseline)
            sync().checkAndNotify()
            sent.clear()

            // Three new ticks arrive at once (catchup), transiently AMBER then back below threshold —
            // only the final phase (still AMBER here) vs. the pre-batch phase (GREEN) matters.
            db.tickInputDao().insert(tickInputRow("2026-08-04", composite = 50.0, isCatchup = true))
            db.tickInputDao().insert(tickInputRow("2026-08-05", composite = 60.0, isCatchup = true))
            db.tickInputDao().insert(tickInputRow("2026-08-06", composite = 55.0, isCatchup = true))

            sync().checkAndNotify()

            val phaseNotifs = sent.filter { it.channelId == NotificationChannels.PHASE_TRANSITION }
            assertEquals("3 new ticks must yield exactly 1 notification, not 3", 1, phaseNotifs.size)
            assertTrue(phaseNotifs.single().text.contains("3일"))
        }

    // ---------------------------------------------------------------------- tick_failure

    @Test
    fun `no failed run_log rows means no tick_failure notification`() =
        runTest {
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-05", ranAt = 0L, status = "success", detail = null),
            )

            sync().checkAndNotify()

            assertTrue(sent.none { it.channelId == NotificationChannels.TICK_FAILURE })
        }

    @Test
    fun `a failed run_log row notifies once`() =
        runTest {
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-05", ranAt = 0L, status = "failed", detail = "NET_OFFLINE"),
            )

            sync().checkAndNotify()

            val failureNotifs = sent.filter { it.channelId == NotificationChannels.TICK_FAILURE }
            assertEquals(1, failureNotifs.size)
            assertEquals("NET_OFFLINE", failureNotifs.single().text)
        }

    @Test
    fun `a second failure on the same KST day stays within the 1-per-day notification budget`() =
        runTest {
            val clock = Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneId.of("UTC")) // 17:00 KST
            db.runLogDao().insert(failedRunLog(date = "2026-08-05", ranAt = 0L, detail = "first"))
            sync(clock).checkAndNotify()
            sent.clear()

            db.runLogDao().insert(failedRunLog(date = "2026-08-05", ranAt = 1L, detail = "second"))
            sync(clock).checkAndNotify()

            assertTrue(
                "budget: at most 1 tick_failure notification per KST day",
                sent.none { it.channelId == NotificationChannels.TICK_FAILURE },
            )
        }

    @Test
    fun `a failure on the following KST day resets the daily budget`() =
        runTest {
            val day1 = Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneId.of("UTC"))
            val day2 = Clock.fixed(Instant.parse("2026-08-06T08:00:00Z"), ZoneId.of("UTC"))
            db.runLogDao().insert(failedRunLog(date = "2026-08-05", ranAt = 0L, detail = "day1"))
            sync(day1).checkAndNotify()
            sent.clear()

            db.runLogDao().insert(failedRunLog(date = "2026-08-06", ranAt = 1L, detail = "day2"))
            sync(day2).checkAndNotify()

            assertEquals(1, sent.count { it.channelId == NotificationChannels.TICK_FAILURE })
        }
}
