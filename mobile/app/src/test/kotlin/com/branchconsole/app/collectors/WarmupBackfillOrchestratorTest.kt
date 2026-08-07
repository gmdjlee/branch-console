package com.branchconsole.app.collectors

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.lake.LakeDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-04g — 가짜 [Collector](픽스처, 네트워크 금지) 기반 오케스트레이터 검증. 시나리오: 정상
 * 적재, 부분 실패(ERROR·PARTIAL 둘 다), 재실행 멱등, 3년 롤링류 구조적 부재 구분, 미수집
 * 리포트, 전체 실패(Failed) 리포트.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WarmupBackfillOrchestratorTest {
    private lateinit var db: LakeDatabase
    private val fixedToday = LocalDate.of(2026, 8, 7)
    private val clock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun observation(
        seriesId: String,
        field: String,
        asOf: LocalDate,
        value: Double,
    ) = Observation(
        seriesId = seriesId,
        field = field,
        asOf = asOf.atStartOfDay(ZoneOffset.UTC).toInstant(),
        observedAt = fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(),
        source = "fixture",
        value = value,
    )

    private fun fakeCollector(
        collectorId: String,
        seriesIds: List<String>,
        outcome: CollectOutcome,
    ) = object : Collector {
        override val id = collectorId
        override val expectedSeriesIds = seriesIds

        override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome = outcome
    }

    private fun orchestrator(
        collectors: List<Collector>,
        notCollected: List<WarmupSeriesStatus> = emptyList(),
    ) = WarmupBackfillOrchestrator(db.observationDao(), collectors, notCollected, clock)

    @Test
    fun `normal load appends rows for every collector and reports OK`() =
        runTest {
            val fakeA =
                fakeCollector(
                    "fake_a",
                    listOf("^VIX"),
                    CollectOutcome.Ok(listOf(observation("^VIX", "close", LocalDate.of(2026, 8, 5), 15.0))),
                )
            val fakeB =
                fakeCollector(
                    "fake_b",
                    listOf("1001"),
                    CollectOutcome.Ok(listOf(observation("1001", "close", LocalDate.of(2026, 8, 5), 6500.0))),
                )

            val report = orchestrator(listOf(fakeA, fakeB)).run(paddingDays = 10)

            assertEquals(2, report.series.size)
            assertTrue(report.series.all { it.status == WarmupStatus.OK })
            assertEquals(1, report.series.single { it.seriesId == "^VIX" }.rows)

            val stored = db.observationDao().confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = Long.MAX_VALUE)
            assertEquals(1, stored.size)
            assertEquals(15.0, stored.single().value, 0.0)
        }

    @Test
    fun `zero-row series inside a Partial outcome is reported ERROR, not silently OK`() =
        runTest {
            val outcome =
                CollectOutcome.Partial(
                    rows = listOf(observation("1001", "close", LocalDate.of(2026, 8, 5), 6500.0)),
                    failures = listOf(SeriesFailure("vkospi", CollectFailureReason.EmptyOnTradingDay("20260805"))),
                )

            val fake = fakeCollector("pykrx", listOf("1001", "vkospi"), outcome)
            val report = orchestrator(listOf(fake)).run(paddingDays = 10)

            assertEquals(WarmupStatus.OK, report.series.single { it.seriesId == "1001" }.status)
            val vkospi = report.series.single { it.seriesId == "vkospi" }
            assertEquals(WarmupStatus.ERROR, vkospi.status)
            assertEquals(0, vkospi.rows)
        }

    @Test
    fun `a series with some rows and a failure is reported PARTIAL`() =
        runTest {
            val outcome =
                CollectOutcome.Partial(
                    rows =
                        listOf(
                            observation("1001", "close", LocalDate.of(2026, 8, 4), 6500.0),
                            observation("1001", "close", LocalDate.of(2026, 8, 5), 6520.0),
                        ),
                    failures = listOf(SeriesFailure("1001", CollectFailureReason.EmptyOnTradingDay("20260806"))),
                )

            val report = orchestrator(listOf(fakeCollector("pykrx", listOf("1001"), outcome))).run(paddingDays = 10)

            val status = report.series.single()
            assertEquals(WarmupStatus.PARTIAL, status.status)
            assertEquals(2, status.rows)
        }

    @Test
    fun `rerun after interruption is idempotent — no duplicate rows, no crash`() =
        runTest {
            val row = observation("^VIX", "close", LocalDate.of(2026, 8, 5), 15.0)
            val fake = fakeCollector("fake_a", listOf("^VIX"), CollectOutcome.Ok(listOf(row)))
            val orch = orchestrator(listOf(fake))

            orch.run(paddingDays = 10)
            orch.run(paddingDays = 10) // 중단 후 재개를 흉내 — 전체 재실행이지만 결과는 멱등해야 한다.

            val stored = db.observationDao().confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = Long.MAX_VALUE)
            assertEquals(1, stored.size)
        }

    @Test
    fun `resuming after a partial first attempt fills in only the missing collector`() =
        runTest {
            // 1차 시도: KRX만 (Yahoo 전에 중단됐다고 가정).
            val krx =
                fakeCollector(
                    "pykrx",
                    listOf("1001"),
                    CollectOutcome.Ok(listOf(observation("1001", "close", LocalDate.of(2026, 8, 5), 6500.0))),
                )
            orchestrator(listOf(krx)).run(paddingDays = 10)

            // 2차 시도(재개): KRX + Yahoo 둘 다 — KRX는 멱등하게 건너뛰고 Yahoo만 새로 채워진다.
            val yahoo =
                fakeCollector(
                    "yfinance",
                    listOf("^VIX"),
                    CollectOutcome.Ok(listOf(observation("^VIX", "close", LocalDate.of(2026, 8, 5), 15.0))),
                )
            orchestrator(listOf(krx, yahoo)).run(paddingDays = 10)

            val krxRows = db.observationDao().confirmSeries("1001", "close", fromAsOf = 0L, toAsOf = Long.MAX_VALUE)
            val yahooRows = db.observationDao().confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = Long.MAX_VALUE)
            assertEquals(1, krxRows.size) // not duplicated by the 2nd attempt
            assertEquals(1, yahooRows.size) // filled in by the 2nd attempt
        }

    @Test
    fun `structural absence before the requested window is OK, not ERROR`() =
        runTest {
            // BAMLH0A0HYM2류 3년 롤링 윈도 — 요청 구간 시작보다 실제 최초 관측이 늦게 시작.
            val windowStart = fixedToday.minusDays(550)
            val actualEarliest = windowStart.plusDays(200)
            val outcome = CollectOutcome.Ok(listOf(observation("BAMLH0A0HYM2", "value", actualEarliest, 3.1)))

            val fake = fakeCollector("fred", listOf("BAMLH0A0HYM2"), outcome)
            val report = orchestrator(listOf(fake)).run(paddingDays = 550)

            val status = report.series.single()
            assertEquals(WarmupStatus.OK, status.status) // 벤더 한계이지 실패가 아니다.
            assertEquals(
                actualEarliest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                status.structuralAbsenceBefore,
            )
        }

    @Test
    fun `coverage starting at the window start has no structural absence`() =
        runTest {
            val windowStart = fixedToday.minusDays(10)
            val outcome = CollectOutcome.Ok(listOf(observation("^VIX", "close", windowStart, 15.0)))

            val report = orchestrator(listOf(fakeCollector("fake_a", listOf("^VIX"), outcome))).run(paddingDays = 10)

            assertEquals(null, report.series.single().structuralAbsenceBefore)
        }

    @Test
    fun `not-collected series are reported without any collect call`() =
        runTest {
            val defaults = WarmupBackfillOrchestrator.DEFAULT_NOT_COLLECTED
            val report = orchestrator(collectors = emptyList(), notCollected = defaults).run(10)

            assertEquals(1, report.series.size)
            assertTrue(report.series.all { it.status == WarmupStatus.NOT_COLLECTED })
            assertEquals(setOf("KR_CDS_5Y"), report.series.map { it.seriesId }.toSet())
        }

    @Test
    fun `provider-level Failed outcome is reported ERROR for every expected series`() =
        runTest {
            val fake =
                fakeCollector(
                    "pykrx",
                    listOf("1001", "2001"),
                    CollectOutcome.Failed(CollectFailureReason.AuthenticationRequired()),
                )

            val report = orchestrator(listOf(fake)).run(paddingDays = 10)

            assertEquals(2, report.series.size)
            assertTrue(report.series.all { it.status == WarmupStatus.ERROR })
        }

    @Test
    fun `report serializes to JSON in the tick_input warmup_status_json shape`() =
        runTest {
            val outcome = CollectOutcome.Ok(listOf(observation("^VIX", "close", LocalDate.of(2026, 8, 5), 15.0)))

            val report = orchestrator(listOf(fakeCollector("fake_a", listOf("^VIX"), outcome))).run(paddingDays = 10)
            val json = report.toJson()

            assertTrue(json.contains("\"seriesId\":\"^VIX\""))
            assertTrue(json.contains("\"status\":\"OK\""))
        }
}
