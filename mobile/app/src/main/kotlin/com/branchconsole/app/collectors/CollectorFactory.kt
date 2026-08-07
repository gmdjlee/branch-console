package com.branchconsole.app.collectors

import android.content.Context
import com.branchconsole.app.collectors.ecos.EcosCollector
import com.branchconsole.app.collectors.fred.FredCollector
import com.branchconsole.app.collectors.krx.KrxCollector
import com.branchconsole.app.collectors.yahoo.YahooCollector
import com.branchconsole.app.credentials.CredentialsStore

/**
 * MT1-08c/08d/04d — KRX·야후·FRED·ECOS 4어댑터 조립 지점. CDS만 여기 없다(00d 확정 미수집 —
 * [WarmupBackfillOrchestrator.DEFAULT_NOT_COLLECTED]와 동일 근거). ECOS는 00b §7.9로 K-04가
 * 종결돼 실제로 수집되지만, `krx_credit_spread_delta` **지표**는 여전히 결측이다
 * ([com.branchconsole.app.tick.ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS] KDoc·
 * [EcosCollector] KDoc 참조 — Python 정본에 이 지표의 builder가 없어 지표 배선은 별도 과업).
 * 일일 확정 수집(`ProductionConfirmTickWorker.dailyCollect`)과 프리뷰 갱신
 * (`PreviewRefreshUseCase`) 둘 다 이 팩토리를 공유한다 — 조립 로직을 두 곳에 중복시키지 않는다.
 */
object CollectorFactory {
    fun createAll(
        context: Context,
        credentialsStore: CredentialsStore,
    ): List<Collector> =
        listOf(
            KrxCollector.create(context, credentialsStore.krxCredentialsProvider()),
            YahooCollector.create(context),
            FredCollector.create(context, credentialsStore.fredCredentialsProvider()),
            EcosCollector.create(context, credentialsStore.ecosCredentialsProvider()),
        )
}
