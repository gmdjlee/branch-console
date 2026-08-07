package com.branchconsole.app.collectors.krx

import kotlinx.coroutines.delay

/**
 * K-03 SSOT 간격(`configs/sources.yaml` `providers.pykrx.rate_limit.min_interval_s`)을
 * KRX 호출 사이에 강제한다.
 *
 * `KrxClient`에는 호출 간격 개념이 없다 — 재시도 backoff만 있을 뿐(00c 저널 §6 #2,
 * `mobile/krx/PROVENANCE.md` §3.1 재확인) — 그래서 어댑터 계층(여기)이 책임진다.
 *
 * [clock]·[sleep]은 테스트에서 가상 시계로 교체하는 주입 지점이다 — 실제 대기 없이
 * 간격 로직만 검증한다(K-03 준수 증인).
 *
 * ponytail: 단일 인스턴스 내 순차 호출만 가정한다(동시 호출 잠금 없음) — 상향은 동시
 * 수집 경로가 실제로 생기면(MT1-04g 오케스트레이터) 재검토.
 */
class KrxRateLimiter(
    private val minIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = ::delay,
) {
    private var lastCallAtMs: Long? = null

    /** 직전 호출로부터 [minIntervalMs]가 지나지 않았으면 남은 시간만큼 대기한다. */
    suspend fun throttle() {
        val last = lastCallAtMs
        if (last != null) {
            val remaining = minIntervalMs - (clock() - last)
            if (remaining > 0) {
                sleep(remaining)
            }
        }
        lastCallAtMs = clock()
    }
}
