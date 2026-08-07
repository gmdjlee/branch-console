package com.branchconsole.app.collectors.ecos

import android.content.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * K-04 SSOT 로더. `configs/indicators.yaml`의 `krx_credit_spread_delta.source`(`stat_code`·
 * `item_codes`)를 assets(syncConfigs 산출물, [com.branchconsole.app.collectors.krx.KrxRateLimitConfig]/
 * [com.branchconsole.app.collectors.WarmupConfig]와 동일 로딩 경로)에서 읽는다 — item_code를
 * 코드에 하드코딩하지 않는다(00b 저널 §7.9 실측 확정값, CLAUDE.md §1 SSOT 규율).
 *
 * `indicators.yaml`의 `indicators:`는 (`sources.yaml`의 `providers:`와 달리) id로 키가 아니라
 * 리스트라 `id == "krx_credit_spread_delta"`인 항목을 순회로 찾는다.
 */
object EcosSeriesConfig {
    private const val ASSET_PATH = "configs/indicators.yaml"
    private const val INDICATOR_ID = "krx_credit_spread_delta"

    data class ItemCodes(val corpAa3y: String, val ktb3y: String)

    /** @return (stat_code, item_codes). asset·키 부재 시 예외로 실패한다(조용한 기본값 금지). */
    fun load(context: Context): Pair<String, ItemCodes> {
        val source = sourceBlock(context)
        val statCode =
            source["stat_code"] as? String
                ?: error("indicators.$INDICATOR_ID.source.stat_code missing from $ASSET_PATH")
        val itemCodes =
            source["item_codes"] as? Map<*, *>
                ?: error("indicators.$INDICATOR_ID.source.item_codes missing from $ASSET_PATH")
        val corpAa3y =
            itemCodes["corp_aa3y"] as? String
                ?: error("indicators.$INDICATOR_ID.source.item_codes.corp_aa3y missing from $ASSET_PATH")
        val ktb3y =
            itemCodes["ktb_3y"] as? String
                ?: error("indicators.$INDICATOR_ID.source.item_codes.ktb_3y missing from $ASSET_PATH")
        return statCode to ItemCodes(corpAa3y, ktb3y)
    }

    private fun sourceBlock(context: Context): Map<*, *> {
        val root =
            context.assets.open(ASSET_PATH).use {
                Load(LoadSettings.builder().build()).loadFromInputStream(it)
            }
        val indicators =
            (root as? Map<*, *>)?.get("indicators") as? List<*>
                ?: error("indicators missing from $ASSET_PATH")
        val spec =
            indicators.filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == INDICATOR_ID }
                ?: error("indicator '$INDICATOR_ID' missing from $ASSET_PATH")
        return spec["source"] as? Map<*, *> ?: error("indicators.$INDICATOR_ID.source missing from $ASSET_PATH")
    }
}
