package com.branchconsole.app.format

import java.math.BigDecimal
import java.math.RoundingMode

private const val PERCENT_SCALE = 100.0
private const val DISPLAY_DECIMALS = 1

/**
 * K-07 (반올림은 표시 계층에서만) — coverage/composite 같은 내부 `Double`은 이 함수를 거칠
 * 때만 반올림된다. HALF_UP 소수 1자리(M1_PLAN_D.md §4.5 표시 규칙 — 21.0/31.0 -> "67.7%").
 * 홈 화면·프리뷰 갱신 알림 문구가 공유하는 유일한 반올림 지점.
 */
fun formatCoveragePercent(fraction: Double): String =
    BigDecimal(fraction * PERCENT_SCALE).setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP).toPlainString() + "%"

/** composite(0~100 척도) — 결측(null)이면 "N/A". */
fun formatComposite(value: Double?): String =
    value?.let { BigDecimal(it).setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP).toPlainString() } ?: "N/A"
