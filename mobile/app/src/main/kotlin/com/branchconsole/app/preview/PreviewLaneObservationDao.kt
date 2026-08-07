package com.branchconsole.app.preview

import com.branchconsole.lake.ObservationDao
import com.branchconsole.lake.ObservationEntity
import com.branchconsole.lake.SeriesPoint

/**
 * [com.branchconsole.app.tick.ConfirmTickContext.load]의 Stage 1 조회는
 * [ObservationDao.confirmSeries](읽기 지점 ①, `lane = 0` 하드 필터, M1_PLAN_A.md §2.12 (b-0))에
 * 고정 배선돼 있다. 이 어댑터는 그 호출을 [ObservationDao.previewSeries](읽기 지점 ② — `lane
 * IN (0,1)`이되 동일 as_of는 확정이 이긴다)로 되돌려, **13종 지표 빌더
 * ([com.branchconsole.app.tick.buildIndicatorRuntime]) 전체를 한 글자도 다시 쓰지 않고**
 * 프리뷰 레인을 포함한 신선분을 읽게 만든다(docs/plans/M1_PLAN_B.md §5.4.2 "프리뷰 = 확정
 * 틱과 같은 파이프라인, 새 규칙은 0개다" — 차이는 이 어댑터 하나뿐이다).
 *
 * `app.tick` 패키지는 병렬 워커가 MT1-06을 재작업 중이라 그 파일들을 편집하지 않는다(브리프 지시) —
 * `internal`은 Kotlin 모듈(같은 `:app` Gradle 모듈) 단위 가시성이므로, 이 어댑터는 `app.tick`의
 * 내부 선언을 **읽기만** 하고 그 파일들을 한 줄도 고치지 않는다.
 */
internal class PreviewLaneObservationDao(private val delegate: ObservationDao) : ObservationDao {
    override suspend fun insert(observation: ObservationEntity): Long = delegate.insert(observation)

    override suspend fun confirmSeries(
        seriesId: String,
        field: String,
        fromAsOf: Long,
        toAsOf: Long,
    ): List<SeriesPoint> = delegate.previewSeries(seriesId, field, fromAsOf, toAsOf)

    override suspend fun previewSeries(
        seriesId: String,
        field: String,
        fromAsOf: Long,
        toAsOf: Long,
    ): List<SeriesPoint> = delegate.previewSeries(seriesId, field, fromAsOf, toAsOf)
}
