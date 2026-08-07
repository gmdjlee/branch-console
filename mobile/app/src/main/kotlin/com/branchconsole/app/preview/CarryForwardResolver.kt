package com.branchconsole.app.preview

import com.branchconsole.lake.TickInputDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 이월 severity 1건 — [asOfMillis]는 이월 원천 `tick_input` 행의 `trading_date`(확정 시각, UTC
 * epoch millis)다. "이월 · as_of" 스테일 배지용(D-23 §23.3-1)이며, docs/plans/M1_PLAN_A.md
 * §2.12 (b) 코드 스케치의 `Carried` 데이터클래스와 1:1 대응한다.
 */
internal data class Carried(val severity: Int?, val asOfMillis: Long)

/**
 * 읽기 지점 ③(M1_PLAN_A.md §2.12 (b-0)) — carry-forward 이월 원천.
 *
 * **타입 수준 격리**(AD-A5): 생성자가 [TickInputDao] 하나만 받는다 — `ObservationDao`도 Room
 * 쓰기 인터페이스도 받지 않으므로, 이 클래스에는 원장에 쓸 수 있는 참조 자체가 없다(D-23
 * §23.3-1 "이월값은 Room에 새 레코드로 쓰지 않는다"). `tick_input`은 확정 틱 경로에서만
 * 쓰이므로(AT-9, `app.tick.ConfirmTickRunner`만 insert) lane 필터가 구조적으로 불필요하다 —
 * 프리뷰의 자기 결과가 이월 원천으로 되먹임될 경로가 없다(자기참조 부재, §10.1.2).
 *
 * 이월 깊이 = 1(M-43b-ii): [TickInputDao.lastCommittedSeverities]가 `trading_date DESC LIMIT
 * 1` 한 행만 보므로, 그 행에서도 결측이던 지표(JSON null)는 여기서도 결측으로 남는다 — 지표별
 * walk-back은 하지 않는다.
 */
internal class CarryForwardResolver(private val tickInputDao: TickInputDao) {
    suspend fun lastConfirmed(): Map<String, Carried> {
        val row = tickInputDao.lastCommittedSeverities() ?: return emptyMap()
        val asOfMillis = LocalDate.parse(row.tradingDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val severities = Json.parseToJsonElement(row.severitiesJson).jsonObject
        return severities.mapValues { (_, v) ->
            Carried(severity = if (v == JsonNull) null else v.jsonPrimitive.int, asOfMillis = asOfMillis)
        }
    }
}
