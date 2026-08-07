package com.branchconsole.lake

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 상태기계 입력의 유일한 정본 — 확정 틱마다 `Tick`의 4필드를 동결한다(fold가 매번 전량
 * 재생, docs/plans/M1_PLAN_A.md §2.10 AD-A11). Append-only, 하루 1행(`trading_date` PK가
 * 이중 커밋을 물리 차단).
 *
 * ★ 표시 4열이 fold 입력 전부다(`composite`·`distinctAxes`·`anyCrit`·`anyExtreme`) — 나머지는
 * 감사 컬럼이며 구조적으로 판정에 영향을 줄 수 없다. `severitiesJson`은 M-43b-iii로
 * "fold 미입력·감사"에서 **carry-forward 이월 원천(필수 컬럼)**으로 승격됐다 — 결측 지표도
 * `null`로 명시 기록해야 "그때 결측이었다"가 프리뷰에서 재현된다.
 *
 * 감사 컬럼 합집합은 M-49(M1_PLAN_FINAL.md §1.1)를 그대로 따른다: coverage·registry_version·
 * gap_reason·frozen_at·fired_axes·visible_at_by_indicator·is_catchup·warmup_status_json·
 * pit_quality.
 */
@Entity(tableName = "tick_input")
data class TickInputEntity(
    @PrimaryKey
    @ColumnInfo(name = "trading_date")
    val tradingDate: String,
    // ★ fold 입력 4열 — Tick(composite, distinct_axes, any_crit, any_extreme) 1:1.
    @ColumnInfo(name = "composite") val composite: Double?,
    @ColumnInfo(name = "distinct_axes") val distinctAxes: Int,
    @ColumnInfo(name = "any_crit") val anyCrit: Boolean,
    @ColumnInfo(name = "any_extreme") val anyExtreme: Boolean,
    // carry-forward 이월 원천(M-43b-iii) — 결측 지표도 null로 명시 기록.
    @ColumnInfo(name = "severities_json") val severitiesJson: String,
    // 감사 합집합(M-49).
    @ColumnInfo(name = "coverage") val coverage: Double,
    @ColumnInfo(name = "registry_version") val registryVersion: String,
    @ColumnInfo(name = "gap_reason") val gapReason: String?,
    @ColumnInfo(name = "frozen_at") val frozenAt: Long,
    @ColumnInfo(name = "fired_axes") val firedAxes: String?,
    @ColumnInfo(name = "visible_at_by_indicator") val visibleAtByIndicator: String?,
    @ColumnInfo(name = "is_catchup") val isCatchup: Boolean,
    @ColumnInfo(name = "warmup_status_json") val warmupStatusJson: String?,
    @ColumnInfo(name = "pit_quality") val pitQuality: String?,
)
