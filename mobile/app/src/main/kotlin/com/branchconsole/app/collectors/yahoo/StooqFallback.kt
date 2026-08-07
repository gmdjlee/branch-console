package com.branchconsole.app.collectors.yahoo

import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason

/**
 * K-18 폴백 경로 자리 표시자 — Advisor 확정 A-2(비활성 스텁).
 *
 * 2026-08-07 실측(00a 저널 §3~4): `stooq.com/q/d/l/` 벌크 CSV 엔드포인트가 JS PoW(작업증명)
 * 안티봇 챌린지로 전면 차단됐다(심볼 무관, 3종 동일 패턴). 모바일 네이티브(JS 엔진 없음)로는
 * 우회 불가능 — 지정된 3차 폴백도 없어(§4) 심볼 매핑표 자체를 추측으로 채우지 않았다.
 *
 * 활성 폴백은 이 경로가 아니라 FRED 미러(`FredObservationsCollector`, VIXCLS·SP500 — 00a §9)로
 * 대체됐다. 이 객체는 호출부 배선 자리만 유지하며 [ENABLED]가 `false`인 한 항상
 * [FailureReason.DISABLED]로 실패한다. 재활성화 조건: 대체 엔드포인트 실측(Advisor 승인 필요,
 * 00a §4).
 */
object StooqFallback {
    const val ENABLED: Boolean = false

    fun fetchDailyCsv(symbol: String): CollectorResult<Nothing> =
        CollectorResult.Failed(
            reason = FailureReason.DISABLED,
            message =
                "stooq fallback disabled for $symbol — PoW anti-bot block confirmed 2026-08-07 " +
                    "(docs/journal/2026-08-07_MT1-00a_yahoo_stooq.md §3-4)",
        )
}
